package com.doge.ipdf

import kotlin.math.hypot

/**
 * Distance-adaptive smoothing ("chase") filter for stylus input.
 *
 * Each raw sample pulls the filtered point towards itself by a fraction that depends on how far
 * away it is: a tiny tremor barely moves the filtered point, while a deliberate movement catches up
 * quickly. That keeps handwriting steady without the constant lag of a fixed low-pass filter.
 *
 * The filter works in view pixels, not PDF points, so the hand feel stays the same at every zoom
 * level. A strength of 0 disables it completely: every sample is passed through unchanged.
 */
class StrokeStabilizer {
    var x = 0f
        private set
    var y = 0f
        private set

    private var radiusPx = 0f
    private var started = false

    /** Starts a new stroke at the raw pen position, with [radiusPx] as the chase radius. */
    fun start(rawX: Float, rawY: Float, radiusPx: Float) {
        x = rawX
        y = rawY
        this.radiusPx = radiusPx.coerceAtLeast(0f)
        started = true
    }

    fun reset() {
        started = false
        radiusPx = 0f
    }

    /**
     * Feeds one raw sample. Returns true when [x]/[y] should become a new ink point; returns false
     * when the sample is only sub-pixel jitter and would duplicate the previous point.
     */
    fun add(rawX: Float, rawY: Float): Boolean {
        if (!started) return false
        if (radiusPx <= 0f) {
            x = rawX
            y = rawY
            return true
        }
        val dx = rawX - x
        val dy = rawY - y
        val distance = hypot(dx, dy)
        if (distance < MIN_STEP_PX) return false
        val alpha = distance / (distance + radiusPx)
        x += dx * alpha
        y += dy * alpha
        return true
    }

    companion object {
        /** Below this many view pixels a sample is treated as tremor, not motion. */
        private const val MIN_STEP_PX = 0.6f

        /** Chase radius at full strength, in dp (converted with the display density). */
        private const val RADIUS_DP = 14f

        /** Maps the user-facing 0..1 strength to a chase radius in view pixels. */
        fun radiusFor(strength: Float, density: Float): Float {
            val s = strength.coerceIn(0f, 1f)
            return s * s * RADIUS_DP * density
        }
    }
}
