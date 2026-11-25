package io.github.llenoxinn.taptap.android.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

interface NearbyCallbacks {
    fun onEndpointFound(endpointId: String, endpointName: String)
    fun onEndpointLost(endpointId: String)
    fun onConnectionInitiated(endpointId: String, endpointName: String)
    fun onConnectionResult(endpointId: String, success: Boolean)
    fun onDisconnected(endpointId: String)
    fun onBytesReceived(endpointId: String, bytes: ByteArray)
    fun onPayloadSent(endpointId: String, payloadId: Long, success: Boolean)
    fun onTransportError(operation: String, error: Throwable) {}
}

interface NearbyClient {
    fun startAdvertising(endpointName: String, serviceId: String, callbacks: NearbyCallbacks)
    fun stopAdvertising()
    fun startDiscovery(serviceId: String, callbacks: NearbyCallbacks)
    fun stopDiscovery()
    fun requestConnection(localName: String, endpointId: String, callbacks: NearbyCallbacks)
    fun acceptConnection(endpointId: String, callbacks: NearbyCallbacks)
    fun rejectConnection(endpointId: String)
    fun sendBytes(endpointId: String, bytes: ByteArray): Long
    fun disconnect(endpointId: String)
    fun stopAll()
}

class GmsNearbyClient(context: Context) : NearbyClient {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)

    override fun startAdvertising(endpointName: String, serviceId: String, callbacks: NearbyCallbacks) {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(endpointName, serviceId, lifecycleCallback(callbacks), options)
            .addOnFailureListener { callbacks.onTransportError("startAdvertising", it) }
    }

    override fun stopAdvertising() {
        client.stopAdvertising()
    }

    override fun startDiscovery(serviceId: String, callbacks: NearbyCallbacks) {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    callbacks.onEndpointFound(endpointId, info.endpointName)
                }

                override fun onEndpointLost(endpointId: String) {
                    callbacks.onEndpointLost(endpointId)
                }
            },
            options,
        ).addOnFailureListener { callbacks.onTransportError("startDiscovery", it) }
    }

    override fun stopDiscovery() {
        client.stopDiscovery()
    }

    override fun requestConnection(localName: String, endpointId: String, callbacks: NearbyCallbacks) {
        client.requestConnection(localName, endpointId, lifecycleCallback(callbacks))
            .addOnFailureListener {
                callbacks.onTransportError("requestConnection", it)
                callbacks.onConnectionResult(endpointId, false)
            }
    }

    override fun acceptConnection(endpointId: String, callbacks: NearbyCallbacks) {
        client.acceptConnection(
            endpointId,
            object : PayloadCallback() {
                override fun onPayloadReceived(endpointId: String, payload: Payload) {
                    if (payload.type == Payload.Type.BYTES) {
                        payload.asBytes()?.let { callbacks.onBytesReceived(endpointId, it) }
                    }
                }

                override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                    when (update.status) {
                        PayloadTransferUpdate.Status.SUCCESS ->
                            callbacks.onPayloadSent(endpointId, update.payloadId, true)
                        PayloadTransferUpdate.Status.FAILURE,
                        PayloadTransferUpdate.Status.CANCELED ->
                            callbacks.onPayloadSent(endpointId, update.payloadId, false)
                    }
                }
            },
        ).addOnFailureListener { callbacks.onTransportError("acceptConnection", it) }
    }

    override fun rejectConnection(endpointId: String) {
        client.rejectConnection(endpointId)
    }

    override fun sendBytes(endpointId: String, bytes: ByteArray): Long {
        val payload = Payload.fromBytes(bytes)
        client.sendPayload(endpointId, payload)
        return payload.id
    }

    override fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
    }

    override fun stopAll() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
    }

    private fun lifecycleCallback(callbacks: NearbyCallbacks) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            callbacks.onConnectionInitiated(endpointId, connectionInfo.endpointName)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            callbacks.onConnectionResult(endpointId, result.status.isSuccess)
        }

        override fun onDisconnected(endpointId: String) {
            callbacks.onDisconnected(endpointId)
        }
    }
}
