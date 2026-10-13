package org.hermes.duplex

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Telling the wrist that something happened.
 *
 * The point of the watch in this system is not to be looked at. A CLI agent
 * works for minutes at a time and then either finishes or asks for
 * permission, and both used to be discovered by picking a device up and
 * checking — which is the cost this exists to remove.
 *
 * Two distinct alerts, because they need different answers:
 *
 *   [replied]  one short buzz. Something arrived; read it when you like.
 *   [asking]   two firm buzzes and a higher tone. Something is *blocked* on
 *              you, and it stays blocked for three minutes and then fails.
 *
 * Distinct on purpose. An alert that means "look at me eventually" and one
 * that means "a process is stopped until you answer" must not feel the same,
 * or the urgent one gets the habits of the routine one.
 */
object Alerts {

    /**
     * Whether this device should make a sound at all.
     *
     * Set from the same switch that turns reading aloud on, so one control
     * governs "does this phone make noise" -- a phone in a meeting should be
     * silent about both, and having two ways to be quiet is a way to be
     * accidentally loud.
     */
    @Volatile
    var enabled: Boolean = true

    /** A reply landed. */
    fun replied(context: Context) {
        if (!enabled) return
        buzz(context, longArrayOf(0, 40))
        beep(ToneGenerator.TONE_PROP_BEEP, 90)
    }

    /** Something is waiting on a yes or no. */
    fun asking(context: Context) {
        // Not gated on `enabled`. Turning off "read replies aloud" is a
        // request for less chatter, not a request to miss the one event that
        // stops a process until it is answered.
        buzz(context, longArrayOf(0, 90, 120, 90))
        beep(ToneGenerator.TONE_PROP_BEEP2, 180)
    }

    private fun buzz(context: Context, pattern: LongArray) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun vibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /**
     * A short tone, on the notification stream.
     *
     * Built and released per alert rather than held. A ToneGenerator keeps an
     * AudioTrack open for as long as it lives, and on a watch that is a
     * wakelock on the audio path for a sound that lasts a tenth of a second.
     */
    private fun beep(tone: Int, ms: Int) {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            generator.startTone(tone, ms)
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ runCatching { generator.release() } }, (ms + 150).toLong())
        }
    }
}
