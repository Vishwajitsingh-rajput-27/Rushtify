package com.rushtify.app.data.plugin

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the Dolby Atmos badge mapping.
 *
 * The provider module signals a spatial mix by emitting `codec: "atmos"` with
 * `quality: "ATMOS"`. `audioBadge` previously only switched on the quality tier
 * and had no `ATMOS` case, so the badge fell through to `"OPUS"`. The player
 * (`PlayerHost.qualityLabel`), the download badge and offline playback all read
 * this label, so an Atmos track silently displayed as stereo/OPUS everywhere.
 */
@OptIn(UnstableApi::class)
class SegmentedDashBridgeBadgeTest {

    private fun descriptor(codec: String, quality: String) = SegmentedStreamDescriptor(
        stream = SegmentedStreamRef(codec = codec, quality = quality),
    )

    private fun badge(codec: String, quality: String): String =
        SegmentedDashBridge.audioBadgeLabel(descriptor(codec, quality))

    @Test
    fun atmosByCodecAndQualityIsDolbyAtmos() {
        // Exactly what the shipped provider returns for Bohemian Rhapsody.
        assertEquals("DOLBY ATMOS", badge("atmos", "ATMOS"))
    }

    @Test
    fun atmosByCodecAloneIsDolbyAtmos() {
        assertEquals("DOLBY ATMOS", badge("atmos", "UHD"))
        assertEquals("DOLBY ATMOS", badge("ATMOS", "UHD"))
    }

    @Test
    fun atmosByQualityAloneIsDolbyAtmos() {
        assertEquals("DOLBY ATMOS", badge("flac", "ATMOS"))
        assertEquals("DOLBY ATMOS", badge("flac", "DOLBY_ATMOS"))
    }

    @Test
    fun stereoLosslessLabelsAreUnchanged() {
        assertEquals("UHD FLAC", badge("flac", "UHD"))
        assertEquals("UHD FLAC", badge("flac", "HI_RES_96"))
        assertEquals("HD FLAC", badge("flac", "HD"))
    }

    @Test
    fun nonLosslessTiersHaveAccurateLabels() {
        assertEquals("HE-AAC", badge("aac", "LOW"))
        assertEquals("AAC 320", badge("aac", "SD"))
        assertEquals("320k MP3", badge("mp3", "SD"))
        assertEquals("OPUS", badge("opus", "LOW"))
    }
}
