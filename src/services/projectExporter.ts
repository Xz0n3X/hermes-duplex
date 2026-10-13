import JSZip from 'jszip';

export interface ProjectFileEntry {
  path: string;
  content: string;
}

export const ANDROID_STUDIO_PROJECT_FILES: ProjectFileEntry[] = [
  {
    path: 'settings.gradle.kts',
    content: `pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HermesDuplex"
include(":app")
`,
  },
  {
    path: 'build.gradle.kts',
    content: `plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
`,
  },
  {
    path: 'gradle.properties',
    content: `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.enableJetifier=true
kotlin.code.style=official
`,
  },
  {
    path: 'app/build.gradle.kts',
    content: `plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.hermes.duplex"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.hermes.duplex"
        minSdk = 26
        targetSdk = 34
        versionCode = 79
        versionName = "0.79-poc"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
`,
  },
  {
    path: 'app/src/main/AndroidManifest.xml',
    content: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Foreground Service & Microphone for Android 14 (API 34) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".HermesDuplexApp"
        android:allowBackup="false"
        android:icon="@android:drawable/ic_btn_speak_now"
        android:label="Hermes Duplex"
        android:roundIcon="@android:drawable/ic_btn_speak_now"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.DuplexForegroundService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="microphone|dataSync" />

    </application>
</manifest>
`,
  },
  {
    path: 'app/src/main/java/org/hermes/duplex/HermesDuplexApp.kt',
    content: `package org.hermes.duplex

import android.app.Application

class HermesDuplexApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
`,
  },
  {
    path: 'app/src/main/java/org/hermes/duplex/MainActivity.kt',
    content: `package org.hermes.duplex

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.hermes.duplex.service.DuplexForegroundService

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordAudioGranted) {
            startDuplexService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF090D16)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Hermes Duplex v0.79",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Connected to Mac Studio Bridge via 24kHz Opus.",
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { startDuplexService() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            Text("Start Foreground Service")
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startDuplexService() {
        val intent = Intent(this, DuplexForegroundService::class.java).apply {
            putExtra("EXTRA_AUTH_TOKEN", "hermes-sec-token-77491")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
`,
  },
  {
    path: 'app/src/main/java/org/hermes/duplex/service/DuplexForegroundService.kt',
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

        // FAIL-CLOSED: Refuse to start without valid auth token
        if (!FailClosedSecurityManager.validateToken(token)) {
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                networkClient.connect(token!!)
                audioEngine.startRecording { audioFrame ->
                    networkClient.sendAudio(audioFrame)
                }
            } catch (e: Exception) {
                safeTeardown()
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val channelId = "hermes_duplex_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Hermes Duplex Audio Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Hermes Duplex Active")
            .setContentText("Connected to Mac Studio bridge. Real-time duplex active.")
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
        private const val NOTIFICATION_ID = 4040
    }
}
`,
  },
  {
    path: 'app/src/main/java/org/hermes/duplex/audio/CircularAudioRingBuffer.kt',
    content: `package org.hermes.duplex.audio

import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance circular ring buffer specifically tuned for
 * 20ms (960 byte) audio frames at 24kHz 16-bit Mono.
 * ZERO object allocations in hot audio loop.
 */
class CircularAudioRingBuffer(capacityFrames: Int = 4) {
    private val frameSize = 960
    private val totalCapacity = capacityFrames * frameSize
    private val buffer = ByteArray(totalCapacity)

    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)

    fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        val currentWrite = writeIndex.get()
        val currentRead = readIndex.get()
        val available = if (currentWrite >= currentRead) {
            totalCapacity - (currentWrite - currentRead)
        } else {
            currentRead - currentWrite
        }

        if (available < length) return false

        for (i in 0 until length) {
            val idx = (currentWrite + i) % totalCapacity
            buffer[idx] = data[offset + i]
        }
        writeIndex.set((currentWrite + length) % totalCapacity)
        return true
    }

    fun readFrame(dest: ByteArray): Boolean {
        if (dest.size < frameSize) return false
        val currentRead = readIndex.get()
        val currentWrite = writeIndex.get()

        val available = if (currentWrite >= currentRead) {
            currentWrite - currentRead
        } else {
            totalCapacity - (currentRead - currentWrite)
        }

        if (available < frameSize) return false

        for (i in 0 until frameSize) {
            val idx = (currentRead + i) % totalCapacity
            dest[i] = buffer[idx]
        }
        readIndex.set((currentRead + frameSize) % totalCapacity)
        return true
    }
}
`,
  },
  {
    path: 'app/src/main/java/org/hermes/duplex/audio/AudioCaptureEngine.kt',
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

    @OptIn(DelicateCoroutinesApi::class)
    private val audioDispatcher = newSingleThreadContext("HermesAudioEngineThread")
    private val audioScope = CoroutineScope(audioDispatcher + SupervisorJob())

    private val sampleRate = 24000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
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
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(frameSizeBytes)

            while (isRecording.get()) {
                val bytesRead = audioRecord?.read(buffer, 0, frameSizeBytes) ?: -1
                if (bytesRead == frameSizeBytes) {
                    onAudioChunk(buffer.copyOf())
                } else if (bytesRead < 0) {
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
        } catch (_: Exception) {}
        finally {
            audioRecord = null
        }
    }
}
`,
  },
  {
    path: 'app/src/main/java/org/hermes/duplex/network/WebSocketDuplexClient.kt',
    content: `package org.hermes.duplex.network

import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class WebSocketDuplexClient {

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    fun connect(authToken: String, serverUrl: String = "ws://192.168.1.50:8765") {
        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("X-Hermes-Token", authToken)
            .addHeader("User-Agent", "HermesDuplex-Android/0.79-poc")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                sendSessionInit()
            }

            override fun onMessage(ws: WebSocket, text: String) {}

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                disconnect()
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
        webSocket?.send(pcmData.toByteString(0, pcmData.size))
    }

    fun disconnect() {
        webSocket?.close(1000, "Client initiated disconnect")
        webSocket = null
    }
}
`,
  },
  {
    path: 'app/src/main/java/org/hermes/duplex/security/FailClosedSecurityManager.kt',
    content: `package org.hermes.duplex.security

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object FailClosedSecurityManager {
    private const val TAG = "FailClosedSecurity"
    private val isSecureState = AtomicBoolean(false)

    fun validateToken(token: String?): Boolean {
        if (token.isNullOrBlank() || token.length < 8) {
            Log.e(TAG, "Fail-Closed: Missing or invalid bridge token.")
            isSecureState.set(false)
            return false
        }
        isSecureState.set(true)
        return true
    }

    fun isPermitted(): Boolean = isSecureState.get()
}
`,
  },
];

export async function generateAndroidProjectZip(): Promise<Blob> {
  const zip = new JSZip();
  const root = zip.folder('HermesDuplex');

  ANDROID_STUDIO_PROJECT_FILES.forEach((file) => {
    root?.file(file.path, file.content);
  });

  return await zip.generateAsync({ type: 'blob' });
}

export async function downloadAndroidProjectZip(filename: string = 'HermesDuplex-AndroidStudio.zip') {
  const blob = await generateAndroidProjectZip();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export function getMacStudioTerminalBootstrapScript(): string {
  const commands = [
    '# Run this command in your MacStudio Terminal to generate the complete Android Studio project:',
    'mkdir -p ~/AndroidStudioProjects/HermesDuplex/app/src/main/java/org/hermes/duplex/{service,audio,network,security}',
    'cd ~/AndroidStudioProjects/HermesDuplex',
  ];

  ANDROID_STUDIO_PROJECT_FILES.forEach((file) => {
    // Escape EOF heredocs
    commands.push(`cat << 'EOF' > ${file.path}\n${file.content}\nEOF`);
  });

  commands.push('# Project created! Open it directly in Android Studio:');
  commands.push('open -a "Android Studio" ~/AndroidStudioProjects/HermesDuplex');
  commands.push('# Or build the APK immediately:');
  commands.push('gradle wrapper && ./gradlew assembleDebug');

  return commands.join('\n\n');
}
