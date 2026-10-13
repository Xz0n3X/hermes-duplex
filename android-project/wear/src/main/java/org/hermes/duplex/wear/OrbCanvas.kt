package org.hermes.duplex.wear

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import org.hermes.duplex.OrbModel
import kotlin.math.min

/**
 * The orb, drawn in Compose from the same numbers the phone uses.
 *
 * `OrbModel` in :core owns every decision about how it moves and what colour
 * it is; this file owns only the drawing. That split is what keeps the two
 * apps from slowly becoming two different animations with the same name.
 *
 * The halo is part of the gradient rather than a shadow, for the reason it is
 * on the phone: a blur is clipped by the bounds it is drawn into, and the
 * moment the orb pulses past them the glow squares off.
 */
@Composable
fun OrbCanvas(
    state: OrbModel.State,
    /**
     * Sampled once a frame rather than passed as a value.
     *
     * A number handed in only changes when something recomposes, and the
     * thing this has to follow -- a voice -- moves between recompositions.
     * Read from the frame loop, the orb can beat with speech instead of
     * sitting at whatever level the last state change happened to leave.
     */
    level: () -> Double,
    /**
     * The hue this state was given, or `WearSettings.CYCLE` to sweep.
     *
     * Replaces the whole-wheel rotation that was here: the states are told
     * apart by how the orb moves as much as by colour, so each may be set
     * outright.
     */
    hue: Float,
    modifier: Modifier = Modifier,
) {
    val smoothed = remember { mutableDoubleStateOf(0.0) }

    // One tick per frame, from the animation clock, so it stops when the
    // screen does -- on a watch, a loop that runs while the display is off is
    // a loop that empties the battery by lunchtime.
    val phase by produceState(0.0, state) {
        while (true) {
            withInfiniteAnimationFrameMillis { }
            smoothed.doubleValue = OrbModel.smooth(smoothed.doubleValue, level())
            value += OrbModel.PHASE_STEP
        }
    }

    Canvas(modifier = modifier) {
        val base = min(size.width, size.height) * OrbModel.BASE_FRACTION.toFloat()
        val frame = OrbModel.frame(
            state = state,
            muted = false,
            phase = phase,
            level = smoothed.doubleValue,
            base = base.toDouble(),
        )

        val outer = (frame.radius * OrbModel.HALO).toFloat()
        if (outer <= 0f) return@Canvas
        val core = (1.0 / OrbModel.HALO).toFloat()
        val centre = Offset(size.width / 2f, size.height / 2f)

        // A chosen hue replaces the model's; the model still decides the
        // width of the pair, so the gradient keeps its depth either way.
        val spread = frame.hueB - frame.hueA
        val chosen = if (hue >= WearSettings.CYCLE) sweep(phase) else hue.toDouble()
        val hueA = turn(chosen, 0.0)
        val hueB = turn(chosen + spread, 0.0)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to hsla(hueA, 0.95, 0.72, 0.98),
                    0.55f * core to hsla(hueB, 0.90, 0.58, 0.85),
                    core to hsla(hueB, 0.90, 0.60, 0.42),
                    1f to hsla(hueB, 0.90, 0.60, 0.0),
                ),
                center = centre,
                radius = outer,
            ),
            radius = outer,
            center = centre,
        )
    }
}

/**
 * Every colour, slowly.
 *
 * Slowly on purpose: the web build cycles the wheel fast enough to strobe,
 * which is unpleasant to sit in front of for the twenty seconds a local model
 * can take, and worse on a screen held near the face.
 */
private fun sweep(phase: Double): Double = (phase * 9.0) % 360.0

/**
 * The chosen colour, shown at the size the choice is made.
 *
 * The slider moved a number with nothing to look at, which is no way to pick
 * a colour. This is the same gradient the orb draws, so what is previewed is
 * what is drawn -- not an approximation of it in a swatch.
 */
@Composable
fun OrbSwatch(hue: Float, modifier: Modifier = Modifier) {
    val phase by produceState(0.0, hue) {
        while (true) {
            withInfiniteAnimationFrameMillis { }
            value += OrbModel.PHASE_STEP
        }
    }
    Canvas(modifier = modifier) {
        val shown = if (hue >= WearSettings.CYCLE) sweep(phase) else hue.toDouble()
        val radius = min(size.width, size.height) / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to hsla(shown, 0.95, 0.72, 1.0),
                    0.6f to hsla(turn(shown, 40.0), 0.90, 0.58, 0.95),
                    1f to hsla(turn(shown, 40.0), 0.90, 0.60, 0.15),
                ),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = radius,
            ),
            radius = radius,
            center = Offset(size.width / 2f, size.height / 2f),
        )
    }
}

/** The colour wheel is circular; a shift past 360 comes back around. */
private fun turn(hue: Double, by: Double): Double = ((hue + by) % 360.0 + 360.0) % 360.0

/** CSS `hsla()`. Android's own helper is HSV, which is a different space. */
private fun hsla(hue: Double, saturation: Double, lightness: Double, alpha: Double): Color {
    val rgb = ColorUtils.HSLToColor(
        floatArrayOf(hue.toFloat(), saturation.toFloat(), lightness.toFloat())
    )
    return Color(rgb).copy(alpha = alpha.toFloat())
}
