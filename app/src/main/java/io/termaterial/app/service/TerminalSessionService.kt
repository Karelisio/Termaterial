package io.termaterial.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.termaterial.app.MainActivity
import io.termaterial.app.R

/**
 * Foreground service that keeps this app's process alive (and thus its open
 * [com.termux.terminal.TerminalSession]s, whose I/O threads run independently of any Activity)
 * while at least one shell session is open, even when the app is in the background - "façon
 * Termux" per the task spec.
 *
 * Deliberately does not own the sessions itself (they stay in `MainActivity`'s Compose state, as
 * built in Steps 3-4): a persistent foreground notification only needs the count and title to
 * display, not the sessions themselves, and duplicating session ownership here would be a bigger
 * architectural change for no real benefit at this scope. See docs/step-5-permissions.md.
 */
class TerminalSessionService : Service() {

    private val binder = LocalBinder()
    private var notificationManager: NotificationManager? = null

    inner class LocalBinder : Binder() {
        fun getService(): TerminalSessionService = this@TerminalSessionService
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(sessionCount = 0, activeTitle = null)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Called by MainActivity whenever the open-tab count or the active tab's title changes. */
    fun updateStatus(sessionCount: Int, activeTitle: String?) {
        if (sessionCount <= 0) {
            stopSelf()
            return
        }
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(sessionCount, activeTitle))
    }

    private fun startInForeground(sessionCount: Int, activeTitle: String?) {
        val notification = buildNotification(sessionCount, activeTitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun buildNotification(sessionCount: Int, activeTitle: String?): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val content = if (sessionCount > 1) {
            getString(R.string.notification_content_multi, sessionCount)
        } else {
            getString(R.string.notification_content_single, activeTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "terminal_sessions"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            // Not just startService(): a service that will immediately promote itself to
            // foreground (as onStartCommand() does below) must be started this way from API 26+.
            ContextCompat.startForegroundService(context, Intent(context, TerminalSessionService::class.java))
        }
    }
}
