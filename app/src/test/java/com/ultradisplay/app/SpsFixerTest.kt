package com.ultradisplay.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Real SPS NAL units produced by x264 (baseline / high with B-frames / main with HRD). */
class SpsFixerTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun str(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val samples = mapOf(
        "base" to "000000016742c028d90050065b0110000003001000000788f1832480",
        "high" to "0000000167640032acd94078025ec044000003000400000301e03c60c658",
        "hrd" to "00000001674d4028eca028032d8088000003000800000303c31200016e360002dc6f169c03c60c6580",
        "novui" to "000000016742c028d900500659"
    )

    /**
     * Expected outputs, checked with ffmpeg trace_headers and a full decode. x264 already declares its
     * reordering, so those stay as they are; an SPS without VUI (what hardware encoders often emit)
     * gains bitstream_restriction with max_num_reorder_frames = 0.
     */
    private val expected = mapOf(
        "base" to "000000016742c028d90050065b0110000003001000000788f1832480",
        "high" to "0000000167640032acd94078025ec044000003000400000301e03c60c658",
        "hrd" to "00000001674d4028eca028032d8088000003000800000303c31200016e360002dc6f169c03c60c6580",
        "novui" to "000000016742c028d90050065a01b41108c9"
    )

    @Test fun rewritesAndIsStable() {
        for ((name, s) in samples) {
            val once = SpsFixer.fix(hex(s))
            println("SPSFIX $name ${str(once)}")
            assertArrayEquals("$name output", hex(expected.getValue(name)), once)
            assertArrayEquals("$name rewrite is not stable", once, SpsFixer.fix(once))
        }
    }

    @Test fun leavesNonSpsAlone() {
        val pps = hex("0000000168ce3880")
        assertArrayEquals(pps, SpsFixer.fix(pps))
    }
}
