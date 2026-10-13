package org.hermes.duplex.ui

import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.hermes.duplex.DuplexClient
import org.hermes.duplex.R
import org.hermes.duplex.Speaker
import org.hermes.duplex.ui.theme.Palette

/**
 * The orb, folded down over the chat.
 *
 * A layer rather than a second screen, because collapsing it and ending it
 * are different things. Collapsed, the session is still running underneath:
 * the mic is open, replies are still spoken, and the thread fills in behind.
 * Only the headset, held, hangs up.
 *
 * Gestures, as on the web: tap interrupts the reply being spoken; long-press
 * mutes, and the orb goes still and red.
 */
@Composable
fun VoiceStage(session: MainSession) {
    // Hands-free means nobody is touching it, and a screen that sleeps
    // mid-conversation ends the conversation. Only while it is up.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.bgApp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { session.onStageTap() },
                    onLongPress = { session.toggleMute() },
                )
            }
            .systemBarsPadding(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.voice_mode),
                    color = Palette.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    Modifier
                        .clickable { session.showVoiceStage(false) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ico(R.drawable.ic_close, tint = Palette.textSecondary, size = 16.dp)
                    Text("Hide", color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }

            OrbCanvas(
                state = session.orbState,
                muted = session.muted,
                flashAt = session.flashAt,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                level = {
                    // The room while listening; the reply's own loudness while
                    // speaking, read straight off the samples on their way to
                    // the speaker rather than a synthetic pulse.
                    if (session.orbState == org.hermes.duplex.OrbModel.State.SPEAKING) {
                        DuplexClient.outputLevel
                    } else {
                        // Speech sits well above the ~200 floor and the meters
                        // top out at 3000; this maps an ordinary voice onto
                        // most of the range without letting a shout peg it.
                        (session.rms / 1200.0).coerceIn(0.0, 1.0)
                    }
                },
            )

            Text(
                text = session.orbLabel.ifEmpty { stringResource(R.string.voice_listening) },
                color = session.orbTone.color(),
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Caption(
                text = session.caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp),
            )

            Text(
                text = session.voiceHint,
                color = Palette.textTertiary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
        }
    }
}

/**
 * The text being said, and a scrub bar made of scrolling it.
 *
 * The caption is the whole reply in one laid-out TextView, so where a person
 * has scrolled to *is* a position in the text -- the line at the middle of
 * the viewport is the one they are looking at. Turning that into a character
 * offset and seeking there gives fast-forward and rewind without a transport
 * bar, which there is no room for over the orb.
 *
 * On settle rather than continuously: a fling crosses hundreds of lines, and
 * re-targeting the synthesiser on each would be a stutter rather than a scrub.
 * `onScrollChange` has no "finished" signal, so rest is inferred -- a beat
 * with no further scroll. Only while something is actually being read:
 * scrolling back through a finished reply is reading, not scrubbing.
 */
@Composable
private fun Caption(text: String, modifier: Modifier) {
    val handler = remember { Handler(Looper.getMainLooper()) }
    val color = Palette.textPrimary.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val body = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setLineSpacing(0f, 1.15f)
            }
            ScrollView(context).apply {
                addView(body)
                val seek = Runnable {
                    val layout = body.layout ?: return@Runnable
                    val caption = body.text?.toString().orEmpty()
                    if (caption.isBlank()) return@Runnable
                    val middle = scrollY + height / 2 - body.top
                    val line = layout.getLineForVertical(middle.coerceAtLeast(0))
                    val offset = layout.getLineStart(line.coerceIn(0, layout.lineCount - 1))
                    Speaker.seek(caption, offset, Speaker.currentKey ?: Speaker.pausedKey)
                }
                setOnScrollChangeListener { _, _, _, _, _ ->
                    if (Speaker.currentKey == null && Speaker.pausedKey == null) return@setOnScrollChangeListener
                    handler.removeCallbacks(seek)
                    handler.postDelayed(seek, SEEK_SETTLE_MS)
                }
            }
        },
        update = { scroll ->
            val body = scroll.getChildAt(0) as TextView
            body.setTextColor(color)
            if (body.text.toString() != text) body.text = text
        },
    )
}

/** Long enough to ride out the tail of a fling, short enough to feel like one gesture. */
private const val SEEK_SETTLE_MS = 220L
