package org.hermes.duplex.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeping the watch connected while nothing is on screen.
 *
 * Its only job is to exist. [WearSession] holds the socket, the alerts and
 * the voice; this puts a foreground notification on the process so Wear does
 * not reclaim it the moment the Activity goes away.
 *
 * That gap was the whole limitation: an agent that works for two minutes and
 * then asks permission was asking a watch whose app had been destroyed
 * somewhere in the first thirty seconds. The buzz never came, the question
 * timed out, and the turn failed for a reason nothing on the wrist could
 * explain.
 *
 * The ongoing notification is deliberately quiet and low. It is the price of
 * staying alive, not something to read.
 */
class WearBridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "wear-bridge"
        private const val ALERT_CHANNEL_ID = "wear-approvals"
        private const val NOTIFICATION_ID = 7
        private const val APPROVAL_ID = 8

        const val ACTION_STOP = "org.hermes.duplex.wear.STOP"

        fun start(context: Context) {
            val intent = Intent(context, WearBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, ongoing())
        WearSession.start(applicationContext)
        // A question also goes into the shade, so it survives the screen
        // being off and is still there when the wrist is next raised. The
        // buzz is the alert; this is the record of it.
        WearSession.onAsked = { model -> postApproval(model) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WearSession.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Restarted if the system kills us: the point of this service is to
        // still be here when the answer arrives.
        return START_STICKY
    }

    private fun channel(id: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }

    private fun open(requestCode: Int) = PendingIntent.getActivity(
        this, requestCode,
        Intent(this, WearActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ongoing(): Notification {
        channel(CHANNEL_ID, "Bridge connection", NotificationManager.IMPORTANCE_MIN)
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, WearBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Listening for replies")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open(0))
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this, android.R.drawable.ic_menu_close_clear_cancel
                    ),
                    "Stop",
                    stop,
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun postApproval(model: WearState) {
        channel(ALERT_CHANNEL_ID, "Approvals", NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).notify(
            APPROVAL_ID,
            Notification.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("${model.approvalTool} wants to run")
                .setContentText(model.approvalSubject)
                .setStyle(Notification.BigTextStyle().bigText(model.approvalSubject))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(open(2))
                .setAutoCancel(true)
                // Answering happens in the app, where the command is on
                // screen in full. Approving from a truncated notification is
                // approving something you did not read.
                .setCategory(Notification.CATEGORY_CALL)
                .build()
        )
    }

    override fun onDestroy() {
        WearSession.onAsked = {}
        super.onDestroy()
    }
}
