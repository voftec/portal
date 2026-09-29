package dev.voftec.airplaytv

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Low-latency H.264 decoder feeding a Surface. Annex-B input, buffers queue
 * straight into MediaCodec; every output frame is rendered immediately
 * (releaseOutputBuffer render=true, no clock sync).
 *
 * Before the surface exists, access units are buffered from the latest IDR
 * onward (bounded) and flushed into the codec once the surface is ready —
 * macOS doesn't resend IDRs often, so without this the first seconds are lost.
 */
class VideoDecoder(private val onAspectRatio: (Int, Int) -> Unit) {

    companion object {
        private const val TAG = "AirPlayTV.Video"
        private const val MAX_PENDING_BYTES = 16 * 1024 * 1024
    }

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var configuredWidth = 0
    private var configuredHeight = 0

    // Pending access units captured before the surface/codec is ready.
    private val pending = ArrayList<ByteArray>()
    private var pendingBytes = 0
    private var pendingHasIdr = false

    @Volatile var reportedWidth = 0
    @Volatile var reportedHeight = 0

    @Synchronized
    fun setSurface(s: Surface?) {
        surface = s
        if (s == null) {
            releaseCodec()
        } else {
            flushPendingIntoCodec()
        }
    }

    @Synchronized
    fun onReportedSize(width: Int, height: Int) {
        reportedWidth = width
        reportedHeight = height
    }

    @Synchronized
    fun feed(data: ByteBuffer, len: Int) {
        val bytes = ByteArray(len)
        data.get(bytes, 0, len)

        if (surface == null || codec == null) {
            val idr = findIdr(bytes)
            if (idr >= 0 || pendingHasIdr || pending.isEmpty()) {
                if (idr >= 0) {
                    // keep from SPS/PPS onward: drop nothing before first VCL —
                    // upstream already prepends SPS/PPS before each IDR.
                    pending.clear()
                    pendingBytes = 0
                    pendingHasIdr = true
                }
                if (pendingBytes + bytes.size <= MAX_PENDING_BYTES) {
                    pending.add(bytes)
                    pendingBytes += bytes.size
                }
            }
            return
        }

        maybeReconfigure(bytes)
        queueToCodec(bytes)
    }

    @Synchronized
    fun reset() {
        pending.clear()
        pendingBytes = 0
        pendingHasIdr = false
        releaseCodec()
        if (surface != null) flushPendingIntoCodec()
    }

    @Synchronized
    fun release() {
        pending.clear()
        pendingBytes = 0
        pendingHasIdr = false
        releaseCodec()
        surface = null
    }

    private fun releaseCodec() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "codec release: $e")
        }
        codec = null
        configuredWidth = 0
        configuredHeight = 0
    }

    /** Index of the first VCL IDR NAL (type 5) in an Annex-B stream, or -1. */
    private fun findIdr(data: ByteArray): Int {
        var i = 0
        while (i + 4 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val nalType = data[i + 4].toInt() and 0x1f
                if (nalType == 5) return i
            }
            i++
        }
        return -1
    }

    /** Extract the first SPS to learn the coded size. */
    private fun extractSps(data: ByteArray): ByteArray? {
        var i = 0
        while (i + 5 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val nalType = data[i + 4].toInt() and 0x1f
                if (nalType == 7) {
                    var end = i + 4
                    while (end + 3 < data.size &&
                        !(data[end] == 0.toByte() && data[end + 1] == 0.toByte() &&
                            data[end + 2] == 0.toByte() && data[end + 3] == 1.toByte())) {
                        end++
                    }
                    return data.copyOfRange(i, end)
                }
            }
            i++
        }
        return null
    }

    private fun maybeReconfigure(firstAu: ByteArray) {
        val sps = extractSps(firstAu)
        var w = if (reportedWidth > 0) reportedWidth else 1920
        var h = if (reportedHeight > 0) reportedHeight else 1080
        if (sps != null) {
            val parsed = SpsParser.parseSize(sps)
            if (parsed != null) { w = parsed.first; h = parsed.second }
        }
        if (codec != null && w == configuredWidth && h == configuredHeight) return

        releaseCodec()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, surface, null, 0)
                start()
            }
            configuredWidth = w
            configuredHeight = h
            Log.i(TAG, "decoder configured ${w}x$h low-latency")
            onAspectRatio(w, h)
            drainOutput()
        } catch (e: Exception) {
            Log.e(TAG, "decoder configure failed: $e")
            codec = null
        }
    }

    private fun flushPendingIntoCodec() {
        val surf = surface ?: return
        if (pending.isEmpty()) return
        val first = pending.first()
        maybeReconfigure(first)
        val c = codec ?: return
        for (au in pending) queueToCodec(au)
        pending.clear()
        pendingBytes = 0
        pendingHasIdr = false
        drainOutput()
    }

    private fun queueToCodec(bytes: ByteArray) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                if (buf.capacity() >= bytes.size) {
                    buf.put(bytes)
                    c.queueInputBuffer(idx, 0, bytes.size, 0, 0)
                } else {
                    Log.w(TAG, "input buffer too small (${buf.capacity()} < ${bytes.size})")
                    c.queueInputBuffer(idx, 0, 0, 0, 0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queueInputBuffer: $e")
        }
        drainOutput()
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            try {
                val out = c.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ||
                    info.size == 0) {
                    c.releaseOutputBuffer(out, false)
                    continue
                }
                c.releaseOutputBuffer(out, true)
            } catch (e: Exception) {
                Log.w(TAG, "drainOutput: $e")
                break
            }
        }
        // format change (e.g. mid-session resolution switch in extended mode)
        try {
            val f = c.outputFormat
            val w = f.getInteger(MediaFormat.KEY_WIDTH)
            val h = f.getInteger(MediaFormat.KEY_HEIGHT)
            if (w != configuredWidth || h != configuredHeight) {
                configuredWidth = w
                configuredHeight = h
                onAspectRatio(w, h)
            }
        } catch (_: Exception) {}
    }
}

/** Minimal SPS parser: enough to read pic_width/pic_height in MBs. */
object SpsParser {
    fun parseSize(spsAnnexB: ByteArray): Pair<Int, Int>? {
        // strip start code
        var start = 0
        if (spsAnnexB.size > 4 && spsAnnexB[0] == 0.toByte() && spsAnnexB[1] == 0.toByte() &&
            spsAnnexB[2] == 0.toByte() && spsAnnexB[3] == 1.toByte()) start = 4
        else if (spsAnnexB.size > 3 && spsAnnexB[0] == 0.toByte() && spsAnnexB[1] == 0.toByte() &&
            spsAnnexB[2] == 1.toByte()) start = 3
        if (start == 0 || spsAnnexB.size <= start) return null

        // unescape emulation-prevention bytes
        val rbsp = ArrayList<Byte>(spsAnnexB.size)
        var zeros = 0
        for (i in start + 1 until spsAnnexB.size) { // skip nal header byte
            val b = spsAnnexB[i]
            if (zeros == 2 && b == 3.toByte()) { zeros = 0; continue }
            zeros = if (b == 0.toByte()) zeros + 1 else 0
            rbsp.add(b)
        }
        val r = rbsp.toByteArray()
        val bits = BitReader(r)
        try {
            val profileIdc = bits.u(8)
            bits.u(8); bits.u(8)
            bits.ue() // seq_parameter_set_id
            if (profileIdc in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                val chroma = bits.ue()
                if (chroma == 3) bits.u(1)
                bits.ue(); bits.ue()
                bits.u(1)
                if (bits.u(1) == 1) {
                    val n = if (chroma != 0) 8 else 12
                    repeat(n) { if (bits.u(1) == 1) skipScalingList(bits) }
                }
            }
            bits.ue() // log2_max_frame_num_minus4
            val poc = bits.ue()
            if (poc == 0) bits.ue()
            else if (poc == 1) {
                bits.u(1); bits.se(); bits.se()
                val cycles = bits.ue()
                repeat(cycles) { bits.se() }
            }
            bits.ue() // max_num_ref_frames
            bits.u(1) // gaps_in_frame_num
            val picWidthMbs = bits.ue() + 1
            val picHeightMapUnits = bits.ue() + 1
            val frameMbsOnly = bits.u(1)
            if (frameMbsOnly == 0) bits.u(1)
            bits.u(1) // direct_8x8
            var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
            if (bits.u(1) == 1) { cropL = bits.ue(); cropR = bits.ue(); cropT = bits.ue(); cropB = bits.ue() }
            val w = picWidthMbs * 16 - (cropL + cropR) * 2
            val h = picHeightMapUnits * 16 * (2 - frameMbsOnly) - (cropT + cropB) * 2 * (2 - frameMbsOnly)
            return Pair(w, h)
        } catch (e: Exception) {
            return null
        }
    }

    private fun skipScalingList(bits: BitReader) {
        var lastScale = 8; var nextScale = 8
        val size = if (true) 16 else 64
        repeat(size) {
            if (nextScale != 0) {
                val delta = bits.se()
                nextScale = (lastScale + delta + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    class BitReader(private val data: ByteArray) {
        private var pos = 0
        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = data[pos / 8].toInt() and 0xff
                v = (v shl 1) or ((byte shr (7 - pos % 8)) and 1)
                pos++
            }
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) { zeros++; if (zeros > 32) throw IllegalStateException() }
            return (1 shl zeros) - 1 + if (zeros > 0) u(zeros) else 0
        }
        fun se(): Int {
            val k = ue()
            return if (k % 2 == 0) -(k / 2) else (k + 1) / 2
        }
    }
}
