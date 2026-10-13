package org.hermes.duplex

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A rolling in-app log, so a bad session can be copied out in one tap
 * instead of reconstructed from memory.
 *
 * Level events are deliberately excluded: they arrive at 10 Hz and would
 * bury everything that matters within seconds.
 */
object EventLog {

    /**
     * Which build this is, set by whichever app is using the log.
     *
     * A library has no versionName of its own -- BuildConfig here would be
     * the core module's, which is not a thing anyone installs. The phone and
     * the watch each report their own, so a pasted debug dump says which one
     * it came from.
     */
    @Volatile
    var appVersion: String = "unknown"

    private const val CAPACITY = 500
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        while (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast("${stamp.format(Date())}  $line")
        // Also to logcat. The in-app log is for a user copying a session out
        // in one tap; this is for anyone holding a cable, and a watch is a
        // device you cannot comfortably read a log on at all. Silence is this
        // feature's failure mode, and silence is what a log is for:
        //     adb logcat -s HermesLog
        android.util.Log.i(TAG, line)
    }

    /** One tag for every line, so a single -s filter shows the whole story. */
    const val TAG = "HermesLog"

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    /** What the phone itself brings to the problem. */
    fun deviceReport(context: Context): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return buildString {
            appendLine("device      : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android     : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("app         : $appVersion")
            appendLine("echo cancel : ${AcousticEchoCanceler.isAvailable()}")
            appendLine("noise suppr : ${NoiseSuppressor.isAvailable()}")
            appendLine("audio mode  : ${audio.mode}")
            appendLine("speakerphone: ${audio.isSpeakerphoneOn}")
            appendLine("wired hs    : ${audio.isWiredHeadsetOn}")
            appendLine("bt sco      : ${audio.isBluetoothScoOn}")
            appendLine(
                "native rate : " +
                    audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            )
            appendLine(
                "native buf  : " +
                    audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            )
        }
    }
}
