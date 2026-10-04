package com.ultradisplay.app

/**
 * Rewrites an H.264 SPS so the decoder knows frames never need reordering
 * (VUI bitstream_restriction: max_num_reorder_frames = 0, max_dec_frame_buffering = refs) when the
 * encoder did not say so itself — typical for hardware encoders.
 *
 * Without this, many hardware decoders assume the worst case and hold several frames in their
 * picture buffer before showing the first one — on a 60 fps stream that alone is ~100 ms of lag.
 * Game streamers such as Moonlight apply the same fix. Any parsing problem returns the input
 * unchanged, so this can never break a stream.
 */
object SpsFixer {
    private class Reader(private val d: ByteArray) {
        var pos = 0 // bit position
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun bit(): Int {
            if (pos >= d.size * 8) throw IllegalStateException("SPS overrun")
            val b = (d[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1; pos++; return b
        }
        fun ue(): Int { var zeros = 0; while (bit() == 0) { zeros++; if (zeros > 31) throw IllegalStateException("bad ue") }
            return ((1 shl zeros) - 1) + if (zeros > 0) bits(zeros) else 0 }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    private class Writer {
        private val out = java.io.ByteArrayOutputStream(); private var cur = 0; private var n = 0
        fun bit(b: Int) { cur = (cur shl 1) or (b and 1); n++; if (n == 8) { out.write(cur); cur = 0; n = 0 } }
        fun bits(v: Int, count: Int) { for (i in count - 1 downTo 0) bit((v ushr i) and 1) }
        fun ue(v: Int) { val x = v + 1; val len = 32 - Integer.numberOfLeadingZeros(x); bits(0, len - 1); bits(x, len) }
        fun se(v: Int) = ue(if (v > 0) 2 * v - 1 else -2 * v)
        fun trailing() { bit(1); while (n != 0) bit(0) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    /** Copies fields from reader to writer as it parses them. */
    private class Copy(val r: Reader, val w: Writer) {
        fun u(n: Int): Int { val v = r.bits(n); w.bits(v, n); return v }
        fun ue(): Int { val v = r.ue(); w.ue(v); return v }
        fun se(): Int { val v = r.se(); w.se(v); return v }
    }

    private fun unescape(b: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream(); var zeros = 0
        for (x in b) {
            val v = x.toInt() and 0xff
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            o.write(v); zeros = if (v == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }

    private fun escape(b: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream(); var zeros = 0
        for (x in b) {
            val v = x.toInt() and 0xff
            if (zeros >= 2 && v <= 3) { o.write(3); zeros = 0 }
            o.write(v); zeros = if (v == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }

    private fun hrd(c: Copy) {
        val cnt = c.ue(); c.u(4); c.u(4)
        repeat(cnt + 1) { c.ue(); c.ue(); c.u(1) }
        c.u(5); c.u(5); c.u(5); c.u(5)
    }

    fun fix(sps: ByteArray): ByteArray = try { rewrite(sps) } catch (e: Exception) {
        Session.log("SPS: " + tr("לא שונה", "unchanged") + " (${e.message})"); sps
    }

    private fun rewrite(input: ByteArray): ByteArray {
        // Skip an Annex-B start code if present, then the NAL header byte (type 7 = SPS).
        var start = 0
        while (start + 3 < input.size && input[start].toInt() == 0) start++
        if (start > 0 && input[start].toInt() == 1) start++ else start = 0
        val header = input[start].toInt() and 0xff
        if (header and 0x1f != 7) return input
        val r = Reader(unescape(input.copyOfRange(start + 1, input.size)))
        val w = Writer(); val c = Copy(r, w)

        val profile = c.u(8); c.u(8); c.u(8); c.ue()
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            val chroma = c.ue(); if (chroma == 3) c.u(1)
            c.ue(); c.ue(); c.u(1)
            if (c.u(1) == 1) {
                repeat(if (chroma == 3) 12 else 8) { i ->
                    if (c.u(1) == 1) { var last = 8; var next = 8
                        repeat(if (i < 6) 16 else 64) { if (next != 0) { val d = c.se(); next = (last + d + 256) % 256 }; if (next != 0) last = next } }
                }
            }
        }
        c.ue() // log2_max_frame_num_minus4
        when (c.ue()) { // pic_order_cnt_type
            0 -> c.ue()
            1 -> { c.u(1); c.se(); c.se(); repeat(c.ue()) { c.se() } }
        }
        val refs = c.ue() // max_num_ref_frames
        c.u(1); c.ue(); c.ue()
        if (c.u(1) == 0) c.u(1) // frame_mbs_only → mb_adaptive
        c.u(1)
        if (c.u(1) == 1) { c.ue(); c.ue(); c.ue(); c.ue() } // cropping

        var mvOver = 1; var bytesDenom = 2; var bitsDenom = 1; var mvH = 16; var mvV = 16
        // If the stream already declares reordering (B-frames), keep it — zeroing it would break frame order.
        var reorder = 0
        val hasVui = r.bit(); w.bit(1)
        if (hasVui == 1) {
            if (c.u(1) == 1) { if (c.u(8) == 255) { c.u(16); c.u(16) } }
            if (c.u(1) == 1) c.u(1)
            if (c.u(1) == 1) { c.u(3); c.u(1); if (c.u(1) == 1) { c.u(8); c.u(8); c.u(8) } }
            if (c.u(1) == 1) { c.ue(); c.ue() }
            if (c.u(1) == 1) { c.u(16); c.u(16); c.u(16); c.u(16); c.u(1) } // 32-bit fields split in two
            val nal = c.u(1); if (nal == 1) hrd(c)
            val vcl = c.u(1); if (vcl == 1) hrd(c)
            if (nal == 1 || vcl == 1) c.u(1)
            c.u(1) // pic_struct_present
            if (r.bit() == 1) { // existing restriction: keep its limits, replace the buffering values
                mvOver = r.bit(); bytesDenom = r.ue(); bitsDenom = r.ue(); mvH = r.ue(); mvV = r.ue(); reorder = r.ue(); r.ue()
            }
        } else {
            repeat(8) { w.bit(0) } // aspect, overscan, signal, chroma loc, timing, nal hrd, vcl hrd, pic_struct
        }
        w.bit(1) // bitstream_restriction_flag
        w.bit(mvOver); w.ue(bytesDenom); w.ue(bitsDenom); w.ue(mvH); w.ue(mvV)
        w.ue(reorder)                                   // max_num_reorder_frames (0 unless declared)
        w.ue(maxOf(refs, reorder, 1))                   // max_dec_frame_buffering
        w.trailing()

        val body = escape(w.bytes())
        val out = ByteArray(start + 1 + body.size)
        System.arraycopy(input, 0, out, 0, start + 1)
        System.arraycopy(body, 0, out, start + 1, body.size)
        return out
    }
}
