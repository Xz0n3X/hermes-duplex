package org.hermes.duplex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import org.hermes.duplex.OrbModel
import kotlin.math.min

/**
 * The orb, drawn from [OrbModel].
 *
 * The motion and the hues live in :core so the watch draws the same orb from
 * the same numbers; this is only the phone's brush. [level] is asked for on
 * every frame rather than passed in, so the orb moves with the reply's
 * loudness without recomposing the screen fifty times a second.
 *
 * Runs a frame loop for as long as it is on screen. A loop that decides when
 * to stop is a loop nothing restarts, which is how the old view ended up
 * frozen on the listening hue.
 */
@Composable
fun OrbCanvas(
    state: OrbModel.State,
    muted: Boolean,
    flashAt: Long,
    modifier: Modifier = Modifier,
    level: () -> Double,
) {
    var frameTick by remember { mutableLongStateOf(0L) }
    val phase = remember { doubleArrayOf(0.0, 0.0) }   // [phase, smoothed level]

    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { frameTick = it }
        }
    }

    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            if (frameTick < 0L) return@Canvas   // a read, so every frame redraws
            phase[0] += OrbModel.PHASE_STEP

            // Smooth towards whatever is making sound right now: the room
            // while listening, the reply while speaking. Muted and thinking
            // have no level of their own and must not twitch to room noise.
            val target = when {
                muted -> 0.0
                state == OrbModel.State.LISTENING || state == OrbModel.State.HELD -> level()
                state == OrbModel.State.SPEAKING -> level()
                else -> 0.0
            }
            phase[1] = OrbModel.smooth(phase[1], target)

            val base = min(size.width, size.height) * OrbModel.BASE_FRACTION.toFloat()
            val sinceTap = if (flashAt == 0L) Long.MAX_VALUE
            else System.currentTimeMillis() - flashAt
            val frame = OrbModel.frame(
                state = state,
                muted = muted,
                phase = phase[0],
                level = phase[1],
                base = base.toDouble(),
                sinceTapMs = sinceTap,
            )
            val r = frame.radius.toFloat()
            if (r <= 0f) return@Canvas

            // The halo is part of the gradient rather than a shadow layer: one
            // circle of radius * HALO, with the core and the falloff both
            // expressed as gradient stops, so there is no edge to clip against.
            val outer = r * OrbModel.HALO.toFloat()
            val core = 1f / OrbModel.HALO.toFloat()
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to hsla(frame.hueA, 0.95, 0.72, 0.98),
                    0.55f * core to hsla(frame.hueB, 0.90, 0.58, 0.85),
                    core to hsla(frame.hueB, 0.90, 0.60, 0.42),
                    1f to hsla(frame.hueB, 0.90, 0.60, 0.0),
                    center = center,
                    radius = outer,
                ),
                radius = outer,
                center = center,
            )
        }
    }
}

/** CSS `hsla()`. Android's own helper is HSV, which is a different space. */
private fun hsla(hue: Double, saturation: Double, lightness: Double, alpha: Double): Color {
    val rgb = ColorUtils.HSLToColor(
        floatArrayOf(hue.toFloat(), saturation.toFloat(), lightness.toFloat())
    )
    val argb = (rgb and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)
    return Color(argb)
}
