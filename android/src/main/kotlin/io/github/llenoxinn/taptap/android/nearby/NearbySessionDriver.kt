package io.github.llenoxinn.taptap.android.nearby

import io.github.llenoxinn.taptap.android.mesh.MeshRuntime
import io.github.llenoxinn.taptap.mesh.EnvelopeCodec
import io.github.llenoxinn.taptap.mesh.Frame
import io.github.llenoxinn.taptap.mesh.FrameCodec
import io.github.llenoxinn.taptap.mesh.MsgId
import io.github.llenoxinn.taptap.mesh.SessionState
import io.github.llenoxinn.taptap.mesh.SyncSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives `mesh-core`'s [SyncSession] over Google Nearby Connections (`P2P_CLUSTER`).
 * Runs one session at a time, waits for outbound frames to flush before disconnecting on DONE,
 * and enforces the duty cycle (20s/40s default, 10s/110s below 20% battery).
 *
 * All state mutations execute on [scope] (which shares `runtime.meshDispatcher`).
 */
class NearbySessionDriver(
    private val runtime: MeshRuntime,
    private val nearby: NearbyClient,
    private val scope: CoroutineScope,
    private val onRadioChanged: (radioActive: Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    private var running = false
    private var lowBattery = false

    private var dutyCycleJob: Job? = null
    private var pendingRequestJob: Job? = null
    private var pendingEndpointId: String? = null
    private var connectingTimeoutJob: Job? = null
    private var sessionTickJob: Job? = null

    private var connectingEndpointId: String? = null
    private var activeEndpointId: String? = null
    private var activePeerToken: Long? = null
    private var session: SyncSession? = null
    private val inFlightPayloads = HashSet<Long>()
    private val pendingOutboxMsgs = HashMap<Long, MsgId>()
    private var disconnectWhenFlushed = false

    private val callbacks = object : NearbyCallbacks {
        override fun onEndpointFound(endpointId: String, endpointName: String) {
            scope.launch { handleEndpointFound(endpointId, endpointName) }
        }

        override fun onEndpointLost(endpointId: String) {
            scope.launch {
                if (pendingEndpointId == endpointId) {
                    pendingRequestJob?.cancel()
                    pendingRequestJob = null
                    pendingEndpointId = null
                }
            }
        }

        override fun onConnectionInitiated(endpointId: String, endpointName: String) {
            scope.launch { handleConnectionInitiated(endpointId, endpointName) }
        }

        override fun onConnectionResult(endpointId: String, success: Boolean) {
            scope.launch { handleConnectionResult(endpointId, success) }
        }

        override fun onDisconnected(endpointId: String) {
            scope.launch { handleDisconnected(endpointId) }
        }

        override fun onBytesReceived(endpointId: String, bytes: ByteArray) {
            scope.launch { handleBytesReceived(endpointId, bytes) }
        }

        override fun onPayloadSent(endpointId: String, payloadId: Long, success: Boolean) {
            scope.launch { handlePayloadSent(endpointId, payloadId, success) }
        }

        override fun onTransportError(operation: String, error: Throwable) {
            onError("$operation: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun start() {
        scope.launch {
            if (running) return@launch
            running = true
            dutyCycleJob = scope.launch {
                while (isActive && running) {
                    val low = lowBattery
                    runtime.node.criticalOnly = low
                    val localName = formatEndpointName(runtime.node.nodeToken)
                    nearby.startAdvertising(localName, SERVICE_ID, callbacks)
                    nearby.startDiscovery(SERVICE_ID, callbacks)
                    onRadioChanged(true)
                    delay(if (low) LOW_BATT_ON_MS else NORMAL_ON_MS)

                    nearby.stopAdvertising()
                    nearby.stopDiscovery()
                    onRadioChanged(false)
                    delay(if (low) LOW_BATT_OFF_MS else NORMAL_OFF_MS)
                }
            }
        }
    }

    fun setLowBattery(low: Boolean) {
        scope.launch {
            lowBattery = low
            runtime.node.criticalOnly = low
        }
    }

    fun stop() {
        scope.launch {
            if (!running) return@launch
            running = false
            dutyCycleJob?.cancel()
            dutyCycleJob = null
            pendingRequestJob?.cancel()
            pendingRequestJob = null
            pendingEndpointId = null
            session?.onDisconnect()
            cleanupSession()
            nearby.stopAll()
            onRadioChanged(false)
        }
    }

    private fun isBusy(): Boolean = activeEndpointId != null || connectingEndpointId != null

    private fun handleEndpointFound(endpointId: String, endpointName: String) {
        if (!running || isBusy()) return
        val peerToken = parseEndpointToken(endpointName) ?: return
        val myToken = runtime.node.nodeToken
        if (peerToken == myToken || runtime.node.inCooldown(peerToken)) return

        if (myToken.toULong() < peerToken.toULong()) {
            beginRequest(endpointId, myToken, peerToken)
        } else if (pendingEndpointId == null) {
            pendingEndpointId = endpointId
            pendingRequestJob = scope.launch {
                delay(TIE_BREAK_DELAY_MS)
                val target = pendingEndpointId
                pendingEndpointId = null
                pendingRequestJob = null
                if (running && target == endpointId && !isBusy() && !runtime.node.inCooldown(peerToken)) {
                    beginRequest(endpointId, runtime.node.nodeToken, peerToken)
                }
            }
        }
    }

    private fun beginRequest(endpointId: String, myToken: Long, peerToken: Long) {
        pendingRequestJob?.cancel()
        pendingRequestJob = null
        pendingEndpointId = null
        connectingEndpointId = endpointId
        activePeerToken = peerToken
        startConnectingTimeout(endpointId)
        nearby.requestConnection(formatEndpointName(myToken), endpointId, callbacks)
    }

    private fun handleConnectionInitiated(endpointId: String, endpointName: String) {
        val peerToken = parseEndpointToken(endpointName)
        if (!running || peerToken == null || runtime.node.inCooldown(peerToken)) {
            nearby.rejectConnection(endpointId)
            return
        }
        val busyWithOther = activeEndpointId != null ||
            (connectingEndpointId != null && connectingEndpointId != endpointId)
        if (busyWithOther) {
            nearby.rejectConnection(endpointId)
            return
        }
        pendingRequestJob?.cancel()
        pendingRequestJob = null
        pendingEndpointId = null
        connectingEndpointId = endpointId
        activePeerToken = peerToken
        startConnectingTimeout(endpointId)
        nearby.acceptConnection(endpointId, callbacks)
    }

    private fun startConnectingTimeout(endpointId: String) {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (connectingEndpointId == endpointId) {
                connectingEndpointId = null
                activePeerToken = null
                nearby.disconnect(endpointId)
            }
        }
    }

    private fun handleConnectionResult(endpointId: String, success: Boolean) {
        if (connectingEndpointId != endpointId) {
            if (success) nearby.disconnect(endpointId)
            return
        }
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null
        if (!success || !running) {
            connectingEndpointId = null
            activePeerToken = null
            return
        }
        connectingEndpointId = null
        activeEndpointId = endpointId
        inFlightPayloads.clear()
        pendingOutboxMsgs.clear()
        disconnectWhenFlushed = false

        val newSession = runtime.node.session()
        session = newSession
        sessionTickJob = scope.launch {
            while (isActive && activeEndpointId == endpointId) {
                delay(TICK_INTERVAL_MS)
                val s = session ?: break
                s.onTick()
                if (s.state.ended) {
                    finishOrFlush(endpointId, s)
                    break
                }
            }
        }
        sendFrames(endpointId, newSession.start())
    }

    private fun handleBytesReceived(endpointId: String, bytes: ByteArray) {
        if (activeEndpointId != endpointId) return
        val s = session ?: return
        val decoded = FrameCodec.decode(bytes)
        val outgoing = if (decoded == null) {
            s.onBytes(bytes)
        } else {
            if (decoded is Frame.Hello) {
                activePeerToken = decoded.nodeToken
            }
            s.onFrame(decoded)
        }
        sendFrames(endpointId, outgoing)
        if (s.state.ended) {
            finishOrFlush(endpointId, s)
        }
    }

    private fun sendFrames(endpointId: String, frames: List<Frame>) {
        for (frame in frames) {
            val encoded = FrameCodec.encode(frame)
            val payloadId = nearby.sendBytes(endpointId, encoded)
            inFlightPayloads += payloadId
            if (frame is Frame.EnvelopeFrame) {
                EnvelopeCodec.decode(frame.bytes)?.msgId?.let { msgId ->
                    pendingOutboxMsgs[payloadId] = msgId
                }
            }
        }
    }

    private fun handlePayloadSent(endpointId: String, payloadId: Long, success: Boolean) {
        inFlightPayloads.remove(payloadId)
        val msgId = pendingOutboxMsgs.remove(payloadId)
        if (success && msgId != null) {
            activePeerToken?.let { token -> runtime.recordSentToPeer(msgId, token) }
        }
        if (disconnectWhenFlushed && activeEndpointId == endpointId && inFlightPayloads.isEmpty()) {
            disconnectActive(endpointId)
        }
    }

    private fun finishOrFlush(endpointId: String, s: SyncSession) {
        val waitForFlush = s.state == SessionState.DONE && inFlightPayloads.isNotEmpty()
        if (waitForFlush) {
            disconnectWhenFlushed = true
        } else {
            disconnectActive(endpointId)
        }
    }

    private fun disconnectActive(endpointId: String) {
        nearby.disconnect(endpointId)
        cleanupSession()
    }

    private fun handleDisconnected(endpointId: String) {
        if (connectingEndpointId == endpointId) {
            connectingTimeoutJob?.cancel()
            connectingTimeoutJob = null
            connectingEndpointId = null
            activePeerToken = null
        }
        if (activeEndpointId == endpointId) {
            session?.onDisconnect()
            cleanupSession()
        }
    }

    private fun cleanupSession() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null
        sessionTickJob?.cancel()
        sessionTickJob = null
        connectingEndpointId = null
        activeEndpointId = null
        activePeerToken = null
        session = null
        inFlightPayloads.clear()
        pendingOutboxMsgs.clear()
        disconnectWhenFlushed = false
    }

    companion object {
        const val SERVICE_ID = "io.github.llenoxinn.taptap.mesh.v1"
        const val NORMAL_ON_MS = 20_000L
        const val NORMAL_OFF_MS = 40_000L
        const val LOW_BATT_ON_MS = 10_000L
        const val LOW_BATT_OFF_MS = 110_000L
        const val TIE_BREAK_DELAY_MS = 1_500L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val TICK_INTERVAL_MS = 1_000L

        fun formatEndpointName(token: Long): String =
            token.toULong().toString(16).padStart(16, '0')

        fun parseEndpointToken(name: String): Long? {
            if (name.length != 16) return null
            return name.toULongOrNull(16)?.toLong()
        }
    }
}
