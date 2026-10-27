package com.doge.ipdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeStabilizerTest {

    @Test
    fun strengthZeroPassesSamplesThroughUnchanged() {
        val stabilizer = StrokeStabilizer()
        stabilizer.start(10f, 20f, StrokeStabilizer.radiusFor(0f, 2f))
        assertTrue(stabilizer.add(40f, 60f))
        assertEquals(40f, stabilizer.x, 0.0001f)
        assertEquals(60f, stabilizer.y, 0.0001f)
    }

    @Test
    fun radiusGrowsWithStrengthAndDensity() {
        assertEquals(0f, StrokeStabilizer.radiusFor(0f, 2f), 0.0001f)
        assertEquals(28f, StrokeStabilizer.radiusFor(1f, 2f), 0.001f)
        assertTrue(StrokeStabilizer.radiusFor(0.5f, 2f) < StrokeStabilizer.radiusFor(0.75f, 2f))
        assertEquals(StrokeStabilizer.radiusFor(1f, 2f), StrokeStabilizer.radiusFor(2f, 2f), 0.0001f)
    }

    @Test
    fun subPixelJitterIsDropped() {
        val stabilizer = StrokeStabilizer()
        stabilizer.start(100f, 100f, radiusPx = 30f)
        assertFalse(stabilizer.add(100.3f, 100f))
        assertFalse(stabilizer.add(100f, 100.4f))
        assertEquals(100f, stabilizer.x, 0.0001f)
        assertEquals(100f, stabilizer.y, 0.0001f)
    }

    @Test
    fun smallTremorMovesTheInkOnlySlightly() {
        val stabilizer = StrokeStabilizer()
        stabilizer.start(100f, 100f, radiusPx = 30f)
        assertTrue(stabilizer.add(101f, 100f))
        // 1 px of tremor pulls a 30 px-radius chase by roughly 1/31 of the distance.
        assertTrue(stabilizer.x in 100.02f..100.06f)
        assertEquals(100f, stabilizer.y, 0.0001f)
    }

    @Test
    fun deliberateMoveCatchesUpQuickly() {
        val stabilizer = StrokeStabilizer()
        stabilizer.start(100f, 100f, radiusPx = 30f)
        assertTrue(stabilizer.add(200f, 100f))
        // alpha = 100 / 130 -> the filtered point covers about 77 % of a long move.
        assertTrue(stabilizer.x in 176f..178f)
    }

    @Test
    fun resetStopsTheFilterUntilTheNextStroke() {
        val stabilizer = StrokeStabilizer()
        stabilizer.start(0f, 0f, 30f)
        stabilizer.reset()
        assertFalse(stabilizer.add(5f, 5f))
    }
}
