package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.opencode.OpenCodeConfigHelper
import com.example.domain.model.OpenCodePermissionRequest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Posts local notifications when the app is minimized/backgrounded:
 * - "Агент закончил" when an active OpenCode session transitions from busy to idle.
 * - "Нужно разрешение агента" when OpenCode requests user approval for a tool/action.
 */
class AgentNotificationHelper(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val isForeground = AtomicBoolean(true)

    fun setAppInForeground(inForeground: Boolean) {
        isForeground.set(inForeground)
        if (inForeground) {
            cancelAgentFinishedNotifications()
        }
    }

    fun isAppInForeground(): Boolean = isForeground.get()

    fun notifyAgentFinished(
        sessionId: String,
        sessionTitle: String?,
    ) {
        if (isForeground.get()) return
        if (!canPostNotifications()) return
        runCatching {
            ensureChannels()
            val title = appContext.getString(R.string.notif_agent_finished_title)
            val body = sessionTitle?.trim()?.takeIf { it.isNotBlank() }?.let {
                appContext.getString(R.string.notif_agent_finished_body_session, it)
            } ?: appContext.getString(R.string.notif_agent_finished_body_default)

            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID_AGENT_EVENTS)
                .setSmallIcon(R.drawable.ic_notification_ssh)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(buildOpenAppIntent(requestCode = 5001))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifId = NOTIFICATION_ID_FINISHED_BASE + (sessionId.hashCode() and 0x0FFF)
            nm.notify(TAG_FINISHED, notifId, notification)
        }
    }

    fun notifyPermissionNeeded(request: OpenCodePermissionRequest) {
        if (isForeground.get()) return
        if (!canPostNotifications()) return
        runCatching {
            ensureChannels()
            val title = appContext.getString(R.string.notif_agent_permission_title)
            val detail = listOfNotNull(
                request.title.takeIf { it.isNotBlank() },
                request.command?.takeIf { it.isNotBlank() }?.let { OpenCodeConfigHelper.sanitizeLogs(it).take(120) },
                request.filePath?.takeIf { it.isNotBlank() }?.take(120),
            ).distinct().joinToString(" • ").ifBlank { request.permission }

            val body = appContext.getString(R.string.notif_agent_permission_body, detail)

            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID_AGENT_EVENTS)
                .setSmallIcon(R.drawable.ic_notification_ssh)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(buildOpenAppIntent(requestCode = 5002))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifId = NOTIFICATION_ID_PERMISSION_BASE + (request.id.hashCode() and 0x0FFF)
            nm.notify(TAG_PERMISSION, notifId, notification)
        }
    }

    fun cancelPermissionNotification(requestId: String) {
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifId = NOTIFICATION_ID_PERMISSION_BASE + (requestId.hashCode() and 0x0FFF)
            nm.cancel(TAG_PERMISSION, notifId)
        }
    }

    fun cancelAgentFinishedNotifications() {
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.activeNotifications
                .filter { it.tag == TAG_FINISHED }
                .forEach { nm.cancel(it.tag, it.id) }
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    private fun ensureChannels() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID_AGENT_EVENTS) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID_AGENT_EVENTS,
            appContext.getString(R.string.notif_agent_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.notif_agent_channel_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildOpenAppIntent(requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID_AGENT_EVENTS = "opencode_agent_events"
        private const val TAG_FINISHED = "agent_finished"
        private const val TAG_PERMISSION = "agent_permission"
        private const val NOTIFICATION_ID_FINISHED_BASE = 6000
        private const val NOTIFICATION_ID_PERMISSION_BASE = 7000
    }
}
