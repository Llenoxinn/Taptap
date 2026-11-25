package io.github.llenoxinn.taptap.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.llenoxinn.taptap.android.MainActivity
import io.github.llenoxinn.taptap.android.R
import io.github.llenoxinn.taptap.android.TaptapApp
import io.github.llenoxinn.taptap.android.nearby.GmsNearbyClient
import io.github.llenoxinn.taptap.android.nearby.NearbySessionDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class RelayService : LifecycleService() {
    private val app: TaptapApp get() = application as TaptapApp
    private var driverScope: CoroutineScope? = null
    private var driver: NearbySessionDriver? = null
    private var started = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                val low = pct < LOW_BATTERY_PCT
                app.updateRelayStatus { it.copy(lowBattery = low) }
                driver?.setLowBattery(low)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundNotification(count = 0, lowBattery = false)

        val scope = CoroutineScope(app.meshRuntime.meshDispatcher + SupervisorJob())
        driverScope = scope
        val sessionDriver = NearbySessionDriver(
            runtime = app.meshRuntime,
            nearby = GmsNearbyClient(this),
            scope = scope,
            onRadioChanged = { active -> app.updateRelayStatus { it.copy(radioActive = active) } },
            onError = { err -> app.updateRelayStatus { it.copy(lastError = err) } },
        )
        driver = sessionDriver

        registerBatteryReceiver()
        val initialLow = readBatteryLow()
        app.updateRelayStatus { it.copy(running = true, lowBattery = initialLow, lastError = null) }
        sessionDriver.setLowBattery(initialLow)
        sessionDriver.start()
        started = true

        lifecycleScope.launch {
            combine(app.meshRuntime.carriedCount, app.relayStatus) { count, status ->
                count to status.lowBattery
            }.collect { (count, low) ->
                if (started) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIF_ID, buildNotification(count, low))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            app.prefsStore.setRelayDesired(false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        started = false
        runCatching { unregisterReceiver(batteryReceiver) }
        driver?.stop()
        driverScope?.cancel()
        driver = null
        driverScope = null
        app.updateRelayStatus { it.copy(running = false, radioActive = false) }
        super.onDestroy()
    }

    private fun startForegroundNotification(count: Int, lowBattery: Boolean) {
        val notification = buildNotification(count, lowBattery)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.relay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(count: Int, lowBattery: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RelayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (lowBattery) {
            getString(R.string.relay_low_battery)
        } else {
            getString(R.string.relay_notification_text, count)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_firefly)
            .setContentTitle(getString(R.string.relay_on))
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.relay_stop_action), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(this, batteryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun readBatteryLow(): Boolean {
        val mgr = getSystemService(BatteryManager::class.java) ?: return false
        val pct = mgr.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return pct in 0 until LOW_BATTERY_PCT
    }

    companion object {
        const val ACTION_STOP = "io.github.llenoxinn.taptap.STOP_RELAY"
        private const val CHANNEL_ID = "taptap_relay"
        private const val NOTIF_ID = 1001
        private const val LOW_BATTERY_PCT = 20

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}
