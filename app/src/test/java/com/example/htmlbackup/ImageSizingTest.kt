package com.example.htmlbackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSizingTest {

    @Test
    fun quarterPresetIsOneQuarterOfNormalLongEdge() {
        assertEquals(2048, CompressionPreset.NORMAL.maxDimension)
        assertEquals(512, CompressionPreset.QUARTER.maxDimension)
        assertEquals(
            CompressionPreset.NORMAL.maxDimension / 4,
            CompressionPreset.QUARTER.maxDimension
        )
    }

    @Test
    fun inSampleSizeStaysOneWhenAlreadySmall() {
        assertEquals(1, ImageSizing.inSampleSize(800, 600, 2048))
        assertEquals(1, ImageSizing.inSampleSize(2048, 1536, 2048))
    }

    @Test
    fun inSampleSizeUsesPowerOfTwoForLargeImages() {
        assertEquals(2, ImageSizing.inSampleSize(4096, 3072, 2048))
        assertEquals(8, ImageSizing.inSampleSize(12000, 9000, 2048))
        assertEquals(32, ImageSizing.inSampleSize(12000, 9000, 512))
    }

    @Test
    fun inSampleSizeHandlesInvalidInput() {
        assertEquals(1, ImageSizing.inSampleSize(0, 0, 2048))
        assertEquals(1, ImageSizing.inSampleSize(-5, 100, 2048))
        assertEquals(1, ImageSizing.inSampleSize(100, 100, 0))
    }

    @Test
    fun fitInsidePreservesAspectRatio() {
        assertEquals(2048 to 1536, ImageSizing.fitInside(4096, 3072, 2048))
        assertEquals(512 to 384, ImageSizing.fitInside(4096, 3072, 512))
        assertEquals(384 to 512, ImageSizing.fitInside(3072, 4096, 512))
    }

    @Test
    fun fitInsideLeavesSmallImagesAlone() {
        assertEquals(300 to 200, ImageSizing.fitInside(300, 200, 512))
    }

    @Test
    fun fitInsideNeverReturnsZero() {
        val (width, height) = ImageSizing.fitInside(10000, 1, 512)
        assertTrue(width >= 1)
        assertTrue(height >= 1)
    }
}
