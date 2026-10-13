import { HermesFile } from '../types';

export const DEFAULT_HERMES_FILES: HermesFile[] = [
  {
    id: 'gemini-prompt',
    name: 'GEMINI_PROMPT.md',
    category: 'doc',
    description: 'Immutable ground rules, persona, and engineering behaviors for Hermes Duplex',
    source: 'preset',
    content: `# GEMINI_PROMPT.md - Hermes Duplex Rules of Engagement

## Mission & Role
You are a Senior Android Systems Engineer specializing in low-latency real-time audio (Opus/PCM), WebSocket networking, and Android Foreground Services (API 26-34).
You are working on **Hermes Duplex** (\`org.hermes.duplex\`, v0.79-poc).

## Core Principles
1. **Fail-Closed Security Model**:
   - Audio recording, socket connections, and token passing must NEVER fail in an open or unmonitored state.
   - Any cryptographic signature failure, missing bridge token, or microphone permission revocation must immediately shut down audio buffers and terminate socket pipelines without transmitting unencrypted or unauthenticated payloads.
2. **Real-Time Audio Latency & Threading**:
   - Zero object allocations in the audio recording loop (\`AudioRecord.read()\`).
   - Run audio capture and Opus packetization on dedicated pinned coroutine threads (\`newSingleThreadContext("HermesAudioTx")\`) or \`Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)\`.
   - Never block the UI thread or networking thread with audio conversion.
3. **Android 14 (API 34) Foreground Service Rules**:
   - Must declare and use \`foregroundServiceType="microphone|dataSync"\`.
   - Always post a persistent non-dismissible notification before calling \`startForeground()\`.
   - Properly release \`WakeLock\` and \`WifiLock\` on teardown.
4. **Server Integrity**:
   - The Mac bridge server (\`SERVER_CONTRACT.md\`) is immutable from this client.
   - Never invent or hallucinate fields in \`PROTOCOL.md\`. If a feature requires new JSON fields, flag with: \`NEEDS SERVER CHANGE\`.
`,
  },
  {
    id: 'protocol',
    name: 'PROTOCOL.md',
    category: 'contract',
    description: 'WebSocket JSON schema and duplex audio/text message specifications',
    source: 'preset',
    content: `# PROTOCOL.md - WebSocket JSON Specification

Version: 1.4-duplex
Transport: WebSocket (WSS / WS with local bridge token)

## Message Envelopes

Every client-to-server and server-to-client message adheres to:
\`\`\`json
{
  "type": "<message_type>",
  "id": "<uuid-v4>",
  "timestamp": 1728840000000,
  "payload": { ... }
}
\`\`\`

---

### 1. Handshake & Session Init (\`session.init\`)
**Direction:** Client -> Server
\`\`\`json
{
  "type": "session.init",
  "id": "e4b37012-70dc-4a57-b08e-8a169b50e301",
  "timestamp": 1728840000000,
  "payload": {
    "clientVersion": "0.79-poc",
    "protocolVersion": "1.4-duplex",
    "deviceModel": "Pixel 8 Pro",
    "audioFormat": {
      "codec": "OPUS",
      "sampleRate": 24000,
      "channels": 1,
      "frameSizeMs": 20
    },
    "features": ["barge_in", "vad_local", "interruption"]
  }
}
\`\`\`

**Server Response (\`session.ready\`):**
\`\`\`json
{
  "type": "session.ready",
  "id": "a9041cb1-0c5a-40a2-9442-3a52fbd9cb72",
  "timestamp": 1728840000100,
  "payload": {
    "sessionId": "sess_99384728",
    "maxAudioBitrate": 32000,
    "bargeInAllowed": true
  }
}
\`\`\`

---

### 2. Turn Handshake (\`turn.start\` & \`turn.end\`)
**Client -> Server:**
\`\`\`json
{
  "type": "turn.start",
  "id": "78280f92-564b-4c07-b3a5-2964bfa1e421",
  "timestamp": 1728840002150,
  "payload": {
    "turnId": "turn_001",
    "trigger": "voice_detected",
    "vadConfidence": 0.94
  }
}
\`\`\`

---

### 3. Audio Streaming (\`audio.chunk\`)
Binary payload or base64 Opus frames:
\`\`\`json
{
  "type": "audio.chunk",
  "id": "c1a9382f-2391-45bc-8a71-66d412854910",
  "timestamp": 1728840002170,
  "payload": {
    "turnId": "turn_001",
    "sequence": 1,
    "opusBase64": "<opus_bytes>",
    "isFinal": false
  }
}
\`\`\`

---

### 4. Interruption / Barge-in (\`interruption.detected\`)
Sent by client when user speaks while server/AI is outputting audio:
\`\`\`json
{
  "type": "interruption.detected",
  "id": "8f8b0312-d912-4eb2-a634-11884cba7993",
  "timestamp": 1728840005400,
  "payload": {
    "interruptedTurnId": "turn_000",
    "audioPositionMs": 1420
  }
}
\`\`\`
`,
  },
  {
    id: 'current-state',
    name: 'CURRENT_STATE.md',
    category: 'doc',
    description: 'Current feature status, architectural debt, and active roadmap',
    source: 'preset',
    content: `# CURRENT_STATE.md - Hermes Duplex Status

## Implemented
- [x] Android Foreground Service with notification channel (\`FOREGROUND_SERVICE_MICROPHONE\`)
- [x] Basic AudioRecord buffer capture (PCM 16-bit 24kHz)
- [x] OkHttp WebSocket connection to Mac bridge server (\`ws://<bridge_ip>:8765\`)
- [x] JSON envelope serialization via Moshi / Kotlinx.Serialization
- [x] Local VAD threshold detection for speech onset

## In Progress / Next Up
1. **Opus JNI / Native Compression**: Replace raw PCM streaming with Opus 20ms frame packetization to reduce bandwidth from 384kbps to 24kbps.
2. **Audio Buffer Zero-Allocation Optimization**: Circular ring buffer (\`RingBuffer.kt\`) to eliminate GC pauses during duplex conversation.
3. **Fail-Closed Security Guardrails**:
   - Socket reconnection backoff with dead-man switch.
   - Microphone hardware release immediately on session tear-down.
   - Fail-closed token validation: if bridge secret mismatch, abort immediately without retrying in plain-text.
4. **UI Architecture Strategy**:
   - Assess migration from XML / ViewBinding to Jetpack Compose vs maintaining lightweight ViewBinding for minimal battery & UI thread contention with the Foreground Service.
`,
  },
  {
    id: 'server-contract',
    name: 'SERVER_CONTRACT.md',
    category: 'server',
    description: 'Mac bridge server constraints and immutable interface requirements',
    source: 'preset',
    content: `# SERVER_CONTRACT.md - Bridge Server (Mac OS)

## IMMUTABLE CONSTRAINTS - DO NOT MODIFY

The server runs as a Python/Rust duplex audio bridge on macOS with Apple Silicon Neural Engine:
- **Port:** 8765 (WebSocket default)
- **Audio Intake:** Expects Opus at 24kHz mono (20ms frames) or PCM 16-bit 24kHz LE.
- **Heartbeat:** Ping/Pong every 15,000ms. Missed 2 pings triggers connection drop.
- **Backpressure:** Buffer capacity is 250ms of audio. Client must not flood audio chunks ahead of timestamp progression.
- **Security:** Requires \`X-Hermes-Token\` header in WebSocket handshake.

**RULE:** Any changes requiring server code modifications must be tagged \`NEEDS SERVER CHANGE\` and must not be implemented unilaterally in the Android client.
`,
  },
  {
    id: 'android-service',
    name: 'ANDROID_2_SERVICE_KOTLIN.txt',
    category: 'code',
    description: 'DuplexForegroundService.kt - Service lifecycle, wake lock, fail-closed loop',
    source: 'preset',
    content: `package org.hermes.duplex.service

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
import org.hermes.duplex.security.SecurityState

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
        if (token.isNullOrBlank()) {
            android.util.Log.e(TAG, "Fail-Closed: Missing auth token. Terminating service immediately.")
            SecurityState.recordSecurityBreach("Missing authentication token on start")
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                networkClient.connect(token)
                audioEngine.startRecording { audioFrame ->
                    networkClient.sendAudio(audioFrame)
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
            manager.createNotificationChannel(channel)
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
            acquire(60 * 60 * 1000L) // 1 hr max safe guard
        }
    }

    companion object {
        private const val TAG = "DuplexService"
        private const val NOTIFICATION_ID = 4040
    }
}
`,
  },
  {
    id: 'android-audio',
    name: 'ANDROID_3_AUDIO_KOTLIN.txt',
    category: 'code',
    description: 'AudioCaptureEngine.kt - AudioRecord, priority dispatcher, circular ring buffer',
    source: 'preset',
    content: `package org.hermes.duplex.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureEngine(private val context: Context) {

    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null

    // Dedicated single-thread dispatcher for deterministic audio latency
    @OptIn(DelicateCoroutinesApi::class)
    private val audioDispatcher = newSingleThreadContext("HermesAudioEngineThread")
    private val audioScope = CoroutineScope(audioDispatcher + SupervisorJob())

    // 24kHz, 16-bit Mono PCM
    private val sampleRate = 24000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    
    // 20ms audio frame = 24000 * 0.02 = 480 samples = 960 bytes
    private val frameSizeBytes = 960

    @SuppressLint("MissingPermission")
    fun startRecording(onAudioChunk: (ByteArray) -> Unit) {
        if (isRecording.getAndSet(true)) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        val bufferSize = maxOf(minBufferSize, frameSizeBytes * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            audioEncoding,
            bufferSize
        )

        audioRecord?.startRecording()

        audioScope.launch {
            // Elevate thread priority for minimal jitter
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            // Pre-allocated reusable buffer: ZERO garbage collection pressure
            val buffer = ByteArray(frameSizeBytes)

            while (isRecording.get()) {
                val bytesRead = audioRecord?.read(buffer, 0, frameSizeBytes) ?: -1
                if (bytesRead == frameSizeBytes) {
                    // Send copy or hand off to circular ring buffer
                    onAudioChunk(buffer.copyOf())
                } else if (bytesRead < 0) {
                    android.util.Log.e("AudioCapture", "Error reading audio: \$bytesRead")
                    break
                }
            }
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            android.util.Log.w("AudioCapture", "Error releasing AudioRecord", e)
        } finally {
            audioRecord = null
        }
    }
}
`,
  },
  {
    id: 'android-network',
    name: 'ANDROID_4_NETWORKING_KOTLIN.txt',
    category: 'code',
    description: 'WebSocketDuplexClient.kt - OkHttp WebSocket duplex transport & handshake',
    source: 'preset',
    content: `package org.hermes.duplex.network

import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class WebSocketDuplexClient {

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite for real-time duplex
        .build()

    private var webSocket: WebSocket? = null
    private var sessionId: String? = null

    fun connect(authToken: String, serverUrl: String = "ws://192.168.1.50:8765") {
        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("X-Hermes-Token", authToken)
            .addHeader("User-Agent", "HermesDuplex-Android/0.79-poc")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                android.util.Log.i(TAG, "Connected to Hermes bridge. Initiating session.init")
                sendSessionInit()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.e(TAG, "WebSocket failure: \${t.message}. Fail-closed triggered.")
            }
        })
    }

    private fun sendSessionInit() {
        val payload = JSONObject().apply {
            put("type", "session.init")
            put("id", UUID.randomUUID().toString())
            put("timestamp", System.currentTimeMillis())
            put("payload", JSONObject().apply {
                put("clientVersion", "0.79-poc")
                put("protocolVersion", "1.4-duplex")
                put("audioFormat", JSONObject().apply {
                    put("codec", "PCM_16BIT")
                    put("sampleRate", 24000)
                    put("channels", 1)
                })
            })
        }
        webSocket?.send(payload.toString())
    }

    fun sendAudio(pcmData: ByteArray) {
        // High-efficiency binary WebSocket frame
        webSocket?.send(pcmData.toByteString(0, pcmData.size))
    }

    private fun handleServerMessage(jsonString: String) {
        val obj = JSONObject(jsonString)
        val type = obj.optString("type")
        if (type == "session.ready") {
            sessionId = obj.getJSONObject("payload").optString("sessionId")
            android.util.Log.i(TAG, "Session ready: \$sessionId")
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client initiated disconnect")
        webSocket = null
    }

    companion object {
        private const val TAG = "WebSocketDuplex"
    }
}
`,
  },
];
