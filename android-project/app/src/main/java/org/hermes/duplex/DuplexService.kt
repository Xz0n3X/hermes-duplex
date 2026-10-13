package org.hermes.duplex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Holds the conversation open when the app is not on screen.
 *
 * Android restricts microphone access for background apps, so without a
 * foreground service the session dies the moment the screen times out --
 * quietly, with no error, which is exactly how the first field test ended
 * (two good turns, then "client disconnected" 32 seconds later).
 *
 * The service owns the socket; the activity only attaches callbacks to it.
 */
class DuplexService : Service() {

    companion object {
        const val ACTION_START = "org.hermes.duplex.START"
        const val ACTION_STOP = "org.hermes.duplex.STOP"
        const val EXTRA_URL = "url"

        private const val CHANNEL_ID = "duplex"

        /**
         * A separate channel, at a much higher importance.
         *
         * The session notification is deliberately silent -- it exists to
         * satisfy the platform and offer a way back. A question the agent is
         * blocked on is the opposite: it stops a process until it is
         * answered, and fails on its own after three minutes. Two things that
         * different cannot share a channel, or muting the constant one mutes
         * the urgent one too.
         */
        private const val ALERT_CHANNEL_ID = "duplex-approvals"
        private const val APPROVAL_NOTIFICATION_ID = 42

        /** Put a pending question in front of someone not holding the phone. */
        fun postApproval(context: Context, request: Approvals.Request) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        ALERT_CHANNEL_ID,
                        "Approvals",
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                )
            }
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            // Deliberately not actionable from here. Allowing a command from
            // a notification means approving it without having read it --
            // the shade truncates, and the whole safety of this is that the
            // command itself is what you looked at.
            manager.notify(
                APPROVAL_NOTIFICATION_ID,
                Notification.Builder(context, ALERT_CHANNEL_ID)
                    .setContentTitle("${request.tool} wants to run")
                    .setContentText(request.subject)
                    .setStyle(
                        Notification.BigTextStyle().bigText(request.subject)
                    )
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .build()
            )
        }

        /** Answered, or timed out: take it out of the shade. */
        fun clearApproval(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(APPROVAL_NOTIFICATION_ID)
        }
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, url: String) {
            val intent = Intent(context, DuplexService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DuplexService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private var client: DuplexClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                client?.stop()
                client = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: DuplexClient.lastUrl
                startForeground(NOTIFICATION_ID, buildNotification("Listening"))
                if (client == null) {
                    client = DuplexClient(applicationContext, url).also { it.connect() }
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Voice session",
                    // Low: this notification exists to satisfy the platform and
                    // to offer a way back, not to demand attention.
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        // The way out.
        //
        // Without this the notification could only be tapped to open the app,
        // and the app itself had no Disconnect — so the only way to make it
        // stop listening was to force-close it. An ongoing notification that
        // holds the microphone must carry its own off switch; that is most of
        // what the notification is for.
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DuplexService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.notification_stop),
                    stop,
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        client?.stop()
        client = null
        super.onDestroy()
    }
}
