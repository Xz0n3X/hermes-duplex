package org.hermes.duplex

import kotlin.math.sin

/**
 * The orb, as arithmetic.
 *
 * Everything about how it moves and what colour it is, with nothing about
 * how it is drawn: the phone renders this into a Canvas View, the watch into
 * a Compose Canvas, and neither owns the behaviour. Ported from AgentKit's
 * Voice Mode canvas, and the numbers are its numbers.
 *
 * The states are not decoration. Listening breathes with the room, held is
 * the same motion in amber because the bridge decided the sentence was not
 * finished, thinking drifts, and speaking moves with the reply's actual
 * loudness rather than a synthetic pulse.
 */
object OrbModel {

    enum class State { IDLE, LISTENING, THINKING, WORKING, SPEAKING, HELD }

    /** Radians per frame. AgentKit's `this._phase += 0.05`. */
    const val PHASE_STEP = 0.05

    /** How long the tap flash lasts. AgentKit's TAP_PULSE_MS. */
    const val TAP_PULSE_MS = 420L

    /**
     * Fraction of the smaller edge the orb's core occupies at rest.
     *
     * Sized so the halo still fits at the largest the orb ever gets: the
     * speaking pulse reaches 1.26x and a tap flash 1.5x, and the halo extends
     * nearly as far again beyond that. Too large and the glow is clipped by
     * the view's own bounds, which is what squares off a circle.
     */
    const val BASE_FRACTION = 0.17

    /** How far the halo reaches past the core, as a multiple of radius. */
    const val HALO = 1.9

    /** One frame: the radius to draw, and the two hues to draw it in. */
    data class Frame(val radius: Double, val hueA: Double, val hueB: Double)

    /**
     * Work out one frame.
     *
     * [level] is 0..1 and means whatever the state makes it mean: the room's
     * loudness while listening, the reply's while speaking, nothing at all
     * while thinking or muted. [sinceTapMs] drives the flash that confirms a
     * tap registered; pass Long.MAX_VALUE for no tap.
     */
    fun frame(
        state: State,
        muted: Boolean,
        phase: Double,
        level: Double,
        base: Double,
        sinceTapMs: Long = Long.MAX_VALUE,
    ): Frame {
        var radius: Double
        var hueA: Double
        var hueB: Double

        when {
            muted -> {
                // No pulse, no hue sweep. The stillness is what reads as muted.
                radius = base
                hueA = 0.0
                hueB = 0.0
            }
            state == State.LISTENING -> {
                val breath = 0.06 * sin(phase) + 0.02 * sin(phase * 2.3)
                radius = base * (1 + breath + level * 0.6)
                hueA = 205.0
                hueB = 265.0
            }
            state == State.HELD -> {
                // Listening's motion so it still reads as an open microphone,
                // in amber so it does not read as ordinary listening.
                val breath = 0.06 * sin(phase) + 0.02 * sin(phase * 2.3)
                radius = base * (1 + breath + level * 0.6)
                hueA = 35.0
                hueB = 55.0
            }
            state == State.THINKING -> {
                radius = base * (1 + 0.04 * sin(phase * 2))
                // A drift, not a sweep. The web cycles the whole colour wheel
                // here, which at this frame rate is a rainbow strobe to sit in
                // front of for the twenty seconds a local model takes.
                hueA = 200.0 + 40.0 * sin(phase * 0.35)
                hueB = hueA + 40.0
            }
            state == State.WORKING -> {
                // Working is active tool execution, distinct from the quiet
                // model pause represented by THINKING.
                val pulse = 0.08 * sin(phase * 1.35) + 0.025 * sin(phase * 2.7)
                radius = base * (1 + pulse)
                hueA = 35.0
                hueB = 85.0
            }
            state == State.SPEAKING -> {
                // The same breath as listening, driven by the reply's real
                // loudness. Speech swings wider than a quiet room, so it gets
                // more of the range; the colour says which of the two it is.
                val breath = 0.05 * sin(phase) + 0.02 * sin(phase * 2.3)
                radius = base * (1 + breath + level * 0.9)
                hueA = 150.0
                hueB = 200.0
            }
            else -> {
                radius = base
                hueA = 210.0
                hueB = 250.0
            }
        }

        if (!muted && sinceTapMs in 0 until TAP_PULSE_MS) {
            val through = sinceTapMs / TAP_PULSE_MS.toDouble()
            val bump = sin(through * Math.PI)      // 0 -> 1 -> 0, a quick pop
            radius *= (1 + 0.5 * bump)
            hueA = 155.0                            // spring green = "sent"
            hueB = 175.0
        }

        return Frame(radius, hueA, hueB)
    }

    /** How fast [level] should chase what it was told. */
    fun smooth(current: Double, target: Double): Double = current + (target - current) * 0.25
}
