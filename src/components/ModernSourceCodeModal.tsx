import React, { useState } from 'react';
import {
  X,
  Code,
  Copy,
  Check,
  Download,
  Smartphone,
  Layers,
  Sparkles,
  FileCode,
} from 'lucide-react';

interface ModernSourceCodeModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const ANDROID_SOURCE_FILES = [
  {
    name: 'HermesDuplexScreen.kt',
    tag: 'Jetpack Compose UI',
    code: `package org.hermes.duplex.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hermes.duplex.service.DuplexForegroundService

@Composable
fun HermesDuplexScreen(
    duplexState: DuplexState,
    audioVolume: Float,
    onMicToggle: () -> Unit,
    onBargeIn: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF090D16)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Status Bar Telemetry
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Hermes Duplex • 24kHz Opus",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
                Text(
                    text = "Fail-Closed Secured",
                    color = Color(0xFF34D399),
                    fontSize = 12.sp
                )
            }

            // Modern Fluid Audio Orb with Pulse
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(1f + (audioVolume * 0.4f))
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF06B6D4), Color(0xFF3B82F6), Color(0xFF0F172A))
                        ),
                        shape = CircleShape
                    )
                    .clickable { onBargeIn() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (duplexState == DuplexState.SPEAKING) "Barge-In" else "Listening",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }

            // Bottom Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onBargeIn,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE))
                ) {
                    Text("Barge-In")
                }

                Button(
                    onClick = onMicToggle,
                    modifier = Modifier.weight(1.5f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("Toggle Mic")
                }
            }
        }
    }
}
`,
  },
  {
    name: 'DuplexForegroundService.kt',
    tag: 'Android 14 Service',
    code: `package org.hermes.duplex.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.hermes.duplex.audio.AudioCaptureEngine
import org.hermes.duplex.network.WebSocketDuplexClient

/**
 * Modern Android 14 (API 34) Foreground Service for continuous duplex voice.
 * Adheres strictly to Fail-Closed security.
 */
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
        
        // FAIL-CLOSED: Immediately terminate if token is missing
        if (token.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                networkClient.connect(token)
                audioEngine.startRecording { frame ->
                    networkClient.sendAudio(frame)
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
                "Hermes Duplex Audio",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Hermes Duplex Active")
            .setContentText("Connected to Mac bridge. Low-latency duplex active.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(1001, notification)
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
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesDuplex:WakeLock").apply {
            acquire(3600_000L)
        }
    }
}
`,
  },
  {
    name: 'CircularAudioRingBuffer.kt',
    tag: 'Audio Engine',
    code: `package org.hermes.duplex.audio

import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free zero-allocation ring buffer for 20ms 24kHz audio frames (960 bytes).
 * Eliminates garbage collector stutter during real-time speech.
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
    name: 'AudioPlaybackEngine.kt',
    tag: 'Hardware AEC & AudioTrack',
    code: `package org.hermes.duplex.audio

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build

/**
 * Low-latency duplex audio playback with hardware Acoustic Echo Cancellation (AEC),
 * Noise Suppression (NS), Automatic Gain Control (AGC), and Audio Focus management.
 */
class AudioPlaybackEngine(private val context: Context) {

    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val sampleRate = 24000
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    fun initialize(audioSessionId: Int) {
        requestAudioFocus()

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        val bufferSize = maxOf(minBufferSize, 960 * 4) // 4 frames of 20ms audio

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(audioEncoding)
            .setChannelMask(channelConfig)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(audioSessionId)
            .build()

        // Enable hardware effects if available
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                enabled = true
            }
        }

        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                enabled = true
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            gainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                enabled = true
            }
        }

        audioTrack?.play()
    }

    fun playAudioFrame(pcmData: ByteArray) {
        audioTrack?.write(pcmData, 0, pcmData.size)
    }

    fun stopAndFlush() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            // Safe cleanup
        }
    }

    fun release() {
        stopAndFlush()
        echoCanceler?.release()
        noiseSuppressor?.release()
        gainControl?.release()
        audioTrack?.release()
        audioTrack = null
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    private fun abandonAudioFocus() {
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(null)
    }
}

`,
  },
  {
    name: 'build.gradle.kts',
    tag: 'Gradle Config',
    code: `plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
`,
  },
  {
    name: 'INSTALL_GUIDE.md',
    tag: 'Step-by-Step Guide',
    code: `# Installing Hermes Duplex APK on Your Android Device

## Prerequisites
1. Android phone running Android 8.0+ (API 26 to 34).
2. Developer Options enabled on phone:
   - Settings -> About phone -> Tap 'Build number' 7 times.
   - Settings -> System -> Developer options -> Turn ON 'USB debugging'.

## 1. Quick Install via USB (Android Studio)
1. Connect phone to your Mac/PC with a USB cable.
2. Allow USB Debugging prompt on your phone screen.
3. Open the project in Android Studio.
4. Select your physical phone from the device dropdown.
5. Click the green 'Run' button (Shift + F10).
   Android Studio compiles the APK and installs it directly on your device.

## 2. Command Line (Terminal)
Run in the project root:
\`\`\`bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
\`\`\`

## 3. Wireless Sideloading (Google Drive)
1. Build the APK: \`./gradlew assembleDebug\`
2. Upload \`app-debug.apk\` to your Google Drive.
3. On your Android phone, open Google Drive and tap \`app-debug.apk\`.
4. Allow 'Install unknown apps' if prompted.
5. Tap 'Install'.

## Important: Connecting to Your Mac Bridge
- When running on a physical phone, \`localhost\` refers to the phone itself.
- In Hermes Settings, set Server URL to your Mac's LAN IP:
  \`ws://192.168.1.XXX:8765\` (find via \`ipconfig getifaddr en0\` on your Mac).
`,
  },
];

export const ModernSourceCodeModal: React.FC<ModernSourceCodeModalProps> = ({
  isOpen,
  onClose,
}) => {
  const [selectedIdx, setSelectedIdx] = useState(0);
  const [copied, setCopied] = useState(false);

  if (!isOpen) return null;

  const currentFile = ANDROID_SOURCE_FILES[selectedIdx];

  const handleCopy = () => {
    navigator.clipboard.writeText(currentFile.code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownloadAll = () => {
    const a = document.createElement('a');
    a.href = '/hermes-duplex-android.tar.gz';
    a.download = 'hermes-duplex-android.tar.gz';
    a.click();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-xs select-none">
      <div className="w-full max-w-4xl bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
        {/* Header */}
        <div className="px-5 py-4 border-b border-slate-800 flex items-center justify-between bg-slate-950/60">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-cyan-500/10 border border-cyan-500/20 flex items-center justify-center text-cyan-400">
              <Smartphone className="w-5 h-5" />
            </div>
            <div>
              <h3 className="font-semibold text-slate-100 text-sm">
                Modernized Android Kotlin Architecture
              </h3>
              <p className="text-xs text-slate-400 font-mono">
                Jetpack Compose • Android 14 (API 34) • Zero-Alloc Ring Buffer
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={handleDownloadAll}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium transition cursor-pointer"
            >
              <Download className="w-3.5 h-3.5" />
              Download All
            </button>
            <button
              onClick={onClose}
              className="p-1.5 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition cursor-pointer"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Tab File Navigation */}
        <div className="px-4 pt-3 bg-slate-950 border-b border-slate-800 flex items-center gap-2 overflow-x-auto text-xs">
          {ANDROID_SOURCE_FILES.map((file, idx) => (
            <button
              key={file.name}
              onClick={() => setSelectedIdx(idx)}
              className={`flex items-center gap-2 px-3.5 py-2 rounded-t-lg font-mono transition cursor-pointer ${
                idx === selectedIdx
                  ? 'bg-slate-900 text-cyan-300 border-t border-x border-slate-800 font-medium'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/50'
              }`}
            >
              <FileCode className="w-3.5 h-3.5 text-cyan-400" />
              <span>{file.name}</span>
              <span className="text-[9px] px-1.5 py-0.2 rounded bg-slate-800 text-slate-400">
                {file.tag}
              </span>
            </button>
          ))}
        </div>

        {/* Code Content */}
        <div className="p-4 bg-slate-950 flex-1 overflow-auto relative">
          <button
            onClick={handleCopy}
            className="absolute top-6 right-6 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-800/90 hover:bg-slate-700 text-slate-200 text-xs font-medium transition border border-slate-700 cursor-pointer shadow-md"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            {copied ? 'Copied' : 'Copy Source'}
          </button>
          <pre className="font-mono text-xs text-slate-200 leading-relaxed overflow-x-auto p-2">
            {currentFile.code}
          </pre>
        </div>
      </div>
    </div>
  );
};
