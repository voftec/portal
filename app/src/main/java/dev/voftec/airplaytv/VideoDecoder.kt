package dev.voftec.airplaytv

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Low-latency H.264 decoder feeding a Surface, MediaCodec async mode on a
 * dedicated HandlerThread. Annex-B access units are copied into a bounded
 * queue on the native callback thread (never blocks); onInputBufferAvailable
 * drains the queue, onOutputBufferAvailable renders immediately — including
 * when the Mac desktop is static and no new input arrives.
 *
 * The codec survives surface changes: it is created on the first IDR AU and
 * configured onto a detached SurfaceTexture placeholder until the SurfaceView
 * surface exists. setSurface() swaps the output surface in place; the codec
 * is only recreated on SPS-size change, stream reset or setOutputSurface
 * failure. macOS only sends an IDR at stream start, so stopping the codec on
 * surface loss would leave a new codec waiting forever for one.
 *
 * If the queue exceeds MAX_QUEUED_AUS, everything before the latest IDR is
 * dropped to bound latency.
 */
class VideoDecoder(private val onAspectRatio: (Int, Int) -> Unit) {

    companion object {
        private const val TAG = "AirPlayTV.Video"
        private const val MAX_QUEUED_AUS = 30
    }

    private val lock = ReentrantLock()

    private var codec: MediaCodec? = null
    private var viewSurface: Surface? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var restartOnNextIdr = false
    private var firstFrameLogged = false

    private var codecThread: HandlerThread? = null
    private var codecHandler: Handler? = null

    // Detached-mode placeholder: consumes output while no view surface exists.
    private var placeholderTexture: SurfaceTexture? = null
    private var placeholder: Surface? = null

    // AUs queued for an idle input buffer (codec running, no free slot yet).
    private val queue = ArrayDeque<ByteArray>()
    private val freeInputs = ArrayDeque<Int>()  // free input slots while queue empty

    @Volatile var reportedWidth = 0
    @Volatile var reportedHeight = 0

    private val codecCallback = object : MediaCodec.Callback() {
        private fun isCurrent(c: MediaCodec): Boolean = lock.withLock { c === codec }

        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (!isCurrent(c)) return
            val au = lock.withLock { if (queue.isNotEmpty()) queue.poll() else null }
            if (au != null) {
                submit(c, index, au)
            } else {
                lock.withLock { freeInputs.add(index) }
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int,
                                             info: MediaCodec.BufferInfo) {
            if (!isCurrent(c)) return
            // Nothing is queued to the placeholder — it has no consumer.
            val render = lock.withLock { viewSurface != null } &&
                info.size > 0 &&
                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
            try {
                c.releaseOutputBuffer(index, render)
                if (render && !firstFrameLogged) {
                    firstFrameLogged = true
                    Log.i(TAG, "first frame rendered")
                }
            } catch (e: Exception) {
                Log.w(TAG, "releaseOutputBuffer: $e")
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (!isCurrent(c)) return
            Log.e(TAG, "codec error: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            if (!isCurrent(c)) return
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            lock.withLock {
                configuredWidth = w
                configuredHeight = h
            }
            onAspectRatio(w, h)
        }
    }

    fun setSurface(s: Surface?) {
        val c: MediaCodec?
        lock.withLock {
            viewSurface = s
            c = codec
        }
        val target = s ?: ensurePlaceholder()
        if (c != null) {
            try {
                c.setOutputSurface(target)
                Log.i(TAG, "output surface -> ${if (s != null) "view" else "placeholder"}")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "setOutputSurface: $e")
                markRestart()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "setOutputSurface: $e")
                markRestart()
            }
        }
    }

    private fun markRestart() {
        // setOutputSurface failed: stop the codec; the next IDR recreates it
        // on the new surface.
        stopCodec()
        lock.withLock { restartOnNextIdr = true }
    }

    fun onReportedSize(width: Int, height: Int) {
        reportedWidth = width
        reportedHeight = height
    }

    /** Called from the native video thread. `data` is only valid during the call. */
    fun feed(data: ByteBuffer, len: Int) {
        val bytes = ByteArray(len)
        data.get(bytes, 0, len)

        val hasIdr = findIdr(bytes) >= 0
        lock.withLock {
            if (codec == null && !hasIdr) return  // drop until the first IDR
            if (restartOnNextIdr && !hasIdr) return
            queue.add(bytes)
            while (queue.size > MAX_QUEUED_AUS) {
                dropBeforeLatestIdr()
            }
        }
        if (hasIdr && (lock.withLock { codec == null || restartOnNextIdr } ||
                       spsSizeDiffers(bytes))) {
            restartCodec(bytes)
        }
        offerToCodec()
    }

    private fun spsSizeDiffers(au: ByteArray): Boolean {
        val sps = extractSps(au) ?: return false
        val size = SpsParser.parseSize(sps) ?: return false
        return lock.withLock {
            codec != null &&
                (size.first != configuredWidth || size.second != configuredHeight)
        }
    }

    fun reset() {
        stopCodec()
        lock.withLock {
            queue.clear()
            freeInputs.clear()
            restartOnNextIdr = true
        }
    }

    fun release() {
        stopCodec()
        lock.withLock {
            queue.clear()
            viewSurface = null
            freeInputs.clear()
        }
        lock.withLock {
            placeholder?.release()
            placeholder = null
            placeholderTexture?.release()
            placeholderTexture = null
        }
    }

    private fun ensurePlaceholder(): Surface {
        lock.withLock {
            if (placeholder == null) {
                placeholderTexture = SurfaceTexture(false)
                placeholder = Surface(placeholderTexture)
            }
            return placeholder!!
        }
    }

    private fun submit(c: MediaCodec, index: Int, au: ByteArray) {
        try {
            val buf = c.getInputBuffer(index)
            if (buf != null && buf.capacity() >= au.size) {
                buf.clear()
                buf.put(au)
                c.queueInputBuffer(index, 0, au.size, 0, 0)
            } else {
                c.queueInputBuffer(index, 0, 0, 0, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "queueInputBuffer: $e")
        }
    }

    /** Pair free input slots with queued AUs, on the codec handler thread. */
    private fun offerToCodec() {
        val c = lock.withLock { codec } ?: return
        val handler = codecHandler ?: return
        handler.post {
            lock.withLock {
                if (codec !== c) return@withLock
                while (freeInputs.isNotEmpty() && queue.isNotEmpty()) {
                    submit(c, freeInputs.poll()!!, queue.poll()!!)
                }
            }
        }
    }

    private fun dropBeforeLatestIdr() {
        // find index of the last IDR in the queue and drop everything before it
        var lastIdr = -1
        var i = 0
        for (au in queue) {
            if (findIdr(au) >= 0) lastIdr = i
            i++
        }
        if (lastIdr > 0) repeat(lastIdr) { queue.poll() }
        else queue.poll()
    }

    private fun restartCodec(firstAu: ByteArray) {
        lock.withLock {
            restartOnNextIdr = false
            queue.clear()
            queue.add(firstAu)
        }
        val surf = lock.withLock { viewSurface } ?: ensurePlaceholder()
        var vw = if (reportedWidth > 0) reportedWidth else 1920
        var vh = if (reportedHeight > 0) reportedHeight else 1080
        extractSps(firstAu)?.let { SpsParser.parseSize(it) }?.let {
            vw = it.first; vh = it.second
        }
        lock.withLock {
            if (codec != null && vw == configuredWidth && vh == configuredHeight) {
                return  // already configured at this size
            }
        }
        stopCodec()

        codecThread = HandlerThread("airplay-video-codec").apply { start() }
        codecHandler = Handler(codecThread!!.looper)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh)
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        try {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(codecCallback, codecHandler)
            c.configure(format, surf, null, 0)
            c.start()
            lock.withLock {
                codec = c
                configuredWidth = vw
                configuredHeight = vh
                firstFrameLogged = false
            }
            Log.i(TAG, "decoder configured ${vw}x$vh " +
                "surface=${if (surf === placeholder) "placeholder" else "view"}")
            onAspectRatio(vw, vh)
        } catch (e: Exception) {
            Log.e(TAG, "decoder configure failed: $e")
            codecHandler?.looper?.quitSafely()
            codecThread = null
            codecHandler = null
        }
    }

    private fun stopCodec() {
        val c = lock.withLock { codec } ?: return
        lock.withLock {
            codec = null
            configuredWidth = 0
            configuredHeight = 0
            freeInputs.clear()
        }
        codecHandler?.post {
            try { c.stop(); c.release() } catch (e: Exception) {
                Log.w(TAG, "codec stop/release: $e")
            }
            codecHandler?.looper?.quitSafely()
        }
        codecThread = null
        codecHandler = null
    }

    /** Index of the first VCL IDR NAL (type 5) in an Annex-B stream, or -1. */
    private fun findIdr(data: ByteArray): Int {
        var i = 0
        while (i + 4 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                if (data[i + 4].toInt() and 0x1f == 5) return i
            }
            i++
        }
        return -1
    }

    private fun extractSps(data: ByteArray): ByteArray? {
        var i = 0
        while (i + 5 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                if (data[i + 4].toInt() and 0x1f == 7) {
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
}

/** Minimal SPS parser: enough to read pic_width/pic_height in MBs. */
object SpsParser {
    fun parseSize(spsAnnexB: ByteArray): Pair<Int, Int>? {
        var start = 0
        if (spsAnnexB.size > 4 && spsAnnexB[0] == 0.toByte() && spsAnnexB[1] == 0.toByte() &&
            spsAnnexB[2] == 0.toByte() && spsAnnexB[3] == 1.toByte()) start = 4
        else if (spsAnnexB.size > 3 && spsAnnexB[0] == 0.toByte() && spsAnnexB[1] == 0.toByte() &&
            spsAnnexB[2] == 1.toByte()) start = 3
        if (start == 0 || spsAnnexB.size <= start) return null

        val rbsp = ArrayList<Byte>(spsAnnexB.size)
        var zeros = 0
        for (i in start + 1 until spsAnnexB.size) { // skip nal header byte
            val b = spsAnnexB[i]
            if (zeros == 2 && b == 3.toByte()) { zeros = 0; continue }
            zeros = if (b == 0.toByte()) zeros + 1 else 0
            rbsp.add(b)
        }
        val bits = BitReader(rbsp.toByteArray())
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
        repeat(16) {
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
