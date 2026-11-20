package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.OpenCodeApplication
import com.example.R
import com.example.data.local.AppPreferencesRepository
import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.SshConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SshConnectionForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { ensureNotificationChannel() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? OpenCodeApplication
        if (app == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_DISCONNECT) {
            runCatching { app.container.sshConnectionManager.disconnect() }
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        val currentState = app.container.sshConnectionManager.connectionState.value
        val startedInForeground = runCatching {
            promoteToForeground(buildNotification(currentState))
            true
        }.getOrDefault(false)

        if (!startedInForeground) {
            releaseLocks()
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }

        acquireLocks()
        observeConnectionState(app.container.sshConnectionManager)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateObserverJob?.cancel()
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "OpenCode::SshTunnelWakeLock",
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                // Re-acquired on each active session; 12h safety cap prevents stuck wake lock.
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "OpenCode::SshTunnelWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        runCatching {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        }
    }

    private fun observeConnectionState(connectionManager: SshConnectionManager) {
        if (stateObserverJob?.isActive == true) return
        stateObserverJob = serviceScope.launch {
            connectionManager.connectionState.collect { state ->
                when (state) {
                    is SshConnectionState.Connected,
                    is SshConnectionState.Connecting,
                    is SshConnectionState.Reconnecting,
                    is SshConnectionState.AwaitingHostKeyVerification,
                    -> {
                        acquireLocks()
                        runCatching {
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            nm.notify(NOTIFICATION_ID, buildNotification(state))
                        }
                    }

                    SshConnectionState.Disconnected,
                    is SshConnectionState.Error,
                    -> {
                        stopForegroundAndSelf()
                    }
                }
            }
        }
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundAndSelf() {
        releaseLocks()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fgs_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.fgs_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: SshConnectionState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = Intent(this, SshConnectionForegroundService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val (title, text) = when (state) {
            is SshConnectionState.Connected -> {
                getString(R.string.fgs_notif_title_connected, state.profile.name) to
                    getString(
                        R.string.fgs_notif_text_connected,
                        state.localTunnelPort,
                        state.profile.openCodePort,
                    )
            }

            is SshConnectionState.Connecting -> {
                getString(R.string.fgs_notif_title_connecting, state.profile.name) to
                    getString(R.string.fgs_notif_text_connecting)
            }

            is SshConnectionState.Reconnecting -> {
                getString(R.string.fgs_notif_title_reconnecting, state.profile.name) to
                    getString(R.string.fgs_notif_text_reconnecting, state.attempt)
            }

            is SshConnectionState.AwaitingHostKeyVerification -> {
                getString(R.string.fgs_notif_title_verifying) to
                    getString(R.string.fgs_notif_text_verifying, state.profile.host)
            }

            else -> {
                getString(R.string.app_name) to getString(R.string.fgs_notif_text_connecting)
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_ssh)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                getString(R.string.fgs_action_disconnect),
                disconnectPending,
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "opencode_ssh_channel"
        const val NOTIFICATION_ID = 4096
        const val ACTION_START = "com.example.action.SSH_FGS_START"
        const val ACTION_DISCONNECT = "com.example.action.SSH_FGS_DISCONNECT"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 60L * 1000L

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, SshConnectionForegroundService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, SshConnectionForegroundService::class.java)
            runCatching {
                context.applicationContext.stopService(intent)
            }
        }
    }
}

/**
 * Automatically starts and stops [SshConnectionForegroundService] based on SSH session state
 * and the user's background keep-alive preference.
 * Starts once a connection is established and stays alive across reconnect cycles.
 */
class SshForegroundServiceController(
    private val context: Context,
    private val connectionManager: SshConnectionManager,
    private val preferencesRepository: AppPreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var serviceRequested = false

    fun startObserving() {
        if (started) return
        started = true
        scope.launch {
            combine(
                connectionManager.connectionState,
                preferencesRepository.settings,
            ) { connState, settings ->
                val shouldStart = connState is SshConnectionState.Connected && settings.keepAliveInBackground
                val shouldKeepAlive = serviceRequested &&
                    settings.keepAliveInBackground &&
                    (connState is SshConnectionState.Connected ||
                        connState is SshConnectionState.Reconnecting ||
                        connState is SshConnectionState.Connecting ||
                        connState is SshConnectionState.AwaitingHostKeyVerification)
                shouldStart || shouldKeepAlive
            }.collect { shouldRun ->
                if (shouldRun && !serviceRequested) {
                    serviceRequested = true
                    SshConnectionForegroundService.start(context)
                } else if (!shouldRun && serviceRequested) {
                    serviceRequested = false
                    SshConnectionForegroundService.stop(context)
                }
            }
        }
    }
}
