package com.app.newspaperss.core.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRulesTest {
    @Test
    fun iconsAndSpacersAreNotWorthKeeping() {
        assertTrue(ImageRules.isWorthKeeping(48, 48))
        assertFalse("a thin divider", ImageRules.isWorthKeeping(1200, 47))
        assertFalse(ImageRules.isWorthKeeping(20, 400))
    }

    @Test
    fun subsamplingNeverDecodesBelowTheTargetSize() {
        assertEquals(1, ImageRules.sampleSize(800, 600))
        assertEquals(1, ImageRules.sampleSize(2399, 1000))
        assertEquals(2, ImageRules.sampleSize(2400, 1000))
        assertEquals("the long side decides", 4, ImageRules.sampleSize(3000, 5000))
        for (long in listOf(1201, 2500, 4799, 4800, 9999)) {
            assertTrue(long / ImageRules.sampleSize(long, 10) >= ImageRules.MAX_DIMENSION)
        }
    }

    @Test
    fun scalingKeepsTheAspectRatioAndNeverEnlarges() {
        assertEquals(1200 to 400, ImageRules.scaledSize(3000, 1000))
        assertEquals(800 to 1200, ImageRules.scaledSize(2000, 3000))
        assertEquals(640 to 480, ImageRules.scaledSize(640, 480))
        assertEquals("a sliver keeps a pixel", 1200 to 1, ImageRules.scaledSize(12000, 2))
    }
}
