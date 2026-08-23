package com.pixeltrigger.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoulderDetectionEngineTest {
    @Test
    fun nonRedArmsThenRedFiresOnceUntilRearmed() {
        val engine = ShoulderDetectionEngine()

        assertEquals(ShoulderDetectionEngine.Event.Armed, engine.process(redDetected = false))
        assertEquals(ShoulderDetectionEngine.Event.Fired, engine.process(redDetected = true))
        assertEquals(ShoulderDetectionEngine.Event.None, engine.process(redDetected = true))
        assertEquals(ShoulderDetectionEngine.Event.Armed, engine.process(redDetected = false))
        assertEquals(ShoulderDetectionEngine.Event.Fired, engine.process(redDetected = true))
    }

    @Test
    fun redClassifierAcceptsDominantRedAndRejectsOtherColors() {
        assertTrue(ShoulderDetectionEngine.isRed(sample(220, 40, 35)))
        assertFalse(ShoulderDetectionEngine.isRed(sample(90, 10, 10)))
        assertFalse(ShoulderDetectionEngine.isRed(sample(220, 210, 20)))
        assertFalse(ShoulderDetectionEngine.isRed(sample(30, 200, 30)))
    }

    private fun sample(r: Int, g: Int, b: Int): DetectionEngine.ColorSample {
        val packed = (r shl 16) or (g shl 8) or b
        return DetectionEngine.ColorSample(
            averageRed = r,
            averageGreen = g,
            averageBlue = b,
            whiteRatio = 0f,
            darkRatio = 0f,
            averageLuminance = 0,
            averageChroma = 0,
            probeCount = 1,
            probe0 = packed,
        )
    }
}
