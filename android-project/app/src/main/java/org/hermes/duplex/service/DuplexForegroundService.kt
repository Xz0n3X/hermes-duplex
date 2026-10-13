package org.hermes.duplex.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.hermes.duplex.audio.AudioCaptureEngine
import org.hermes.duplex.network.WebSocketDuplexClient
import org.hermes.duplex.security.FailClosedSecurityManager

class DuplexForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val audioEngine by lazy { AudioCaptureEngine(this) }
    private val networkClient by lazy { WebSocketDuplexClient() }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra("EXTRA_AUTH_TOKEN")
        
        // FAIL-CLOSED SECURITY CHECK
        if (!FailClosedSecurityManager.validateToken(token)) {
            android.util.Log.e(TAG, "Fail-Closed: Missing or invalid auth token. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        FailClosedSecurityManager.initialize {
            safeTeardown()
        }

        serviceScope.launch {
            try {
                networkClient.connect(token!!)
                audioEngine.startRecording { audioFrame ->
                    if (FailClosedSecurityManager.isPermitted()) {
                        networkClient.sendAudio(audioFrame)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Fail-Closed: Critical failure in duplex pipeline", e)
                safeTeardown()
            }
        }

        return START_STICKY
    }

    private fun startInForeground() {
        val channelId = "hermes_duplex_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Hermes Real-Time Voice Duplex",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Hermes Duplex Active")
            .setContentText("Connected to Mac bridge. Low-latency duplex active.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun safeTeardown() {
        audioEngine.stopRecording()
        networkClient.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        safeTeardown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HermesDuplex:AudioWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
    }

    companion object {
        private const val TAG = "DuplexService"
        private const val NOTIFICATION_ID = 4040
    }
}
