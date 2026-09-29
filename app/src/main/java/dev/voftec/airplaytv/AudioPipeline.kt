package dev.voftec.airplaytv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * AAC decode -> low-latency PCM AudioTrack.
 * ct=8 AAC-ELD (screen mirroring), ct=4 AAC-LC. codec_data per upstream's
 * GStreamer caps: AAC-ELD 44100/2 = f8e85000, AAC-LC 44100/2 = 1210.
 * ct=2 ALAC is out of scope (logged and dropped).
 */
class AudioPipeline {

    companion object {
        private const val TAG = "AirPlayTV.Audio"
    }

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var currentCt = -1
    @Volatile private var volume = 1.0f

    @Synchronized
    fun onFormat(ct: Int, spf: Int) {
        if (ct == currentCt && codec != null) return
        release()
        currentCt = ct
        when (ct) {
            8 -> configureAac(aacEld = true)
            4 -> configureAac(aacEld = false)
            2 -> Log.w(TAG, "ALAC (ct=2) sin soporte en v1: se descarta el audio")
            else -> Log.w(TAG, "ct=$ct desconocido: se descarta el audio")
        }
    }

    private fun configureAac(aacEld: Boolean) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2
        )
        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            if (aacEld) MediaCodecInfo.CodecProfileLevel.AACObjectELD
            else MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )
        // codec_data from upstream GStreamer caps (MPEG-4 ISO 14996-3 §1.6.2.1)
        val csd = if (aacEld) byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0x00)
                  else byteArrayOf(0x12, 0x10)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "AAC decoder configure failed: $e")
            codec = null
            return
        }
        val minBuf = AudioTrack.getMinBufferSize(
            44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.setVolume(volume)
        track?.play()
        Log.i(TAG, "audio pipeline up: ${if (aacEld) "AAC-ELD" else "AAC-LC"} 44100/2")
    }

    @Synchronized
    fun feed(data: ByteBuffer, len: Int) {
        val c = codec ?: return
        val bytes = ByteArray(len)
        data.get(bytes, 0, len)
        try {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                if (buf.capacity() >= bytes.size) {
                    buf.put(bytes)
                    c.queueInputBuffer(idx, 0, bytes.size, 0, 0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio queueInputBuffer: $e")
        }
        drain()
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            try {
                val out = c.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                if (info.size > 0) {
                    val buf = c.getOutputBuffer(out)
                    if (buf != null) {
                        val pcm = ByteArray(info.size)
                        buf.get(pcm)
                        track?.write(pcm, 0, pcm.size)
                    }
                }
                c.releaseOutputBuffer(out, false)
            } catch (e: Exception) {
                Log.w(TAG, "audio drain: $e")
                break
            }
        }
    }

    @Synchronized
    fun setVolume(db: Float) {
        // AirPlay dB: -144 = mute, -30..0 usable range
        volume = when {
            db <= -144f -> 0f
            db <= -30f -> 0f
            db >= 0f -> 1f
            else -> (30f + db) / 30f
        }
        try { track?.setVolume(volume) } catch (_: Exception) {}
    }

    @Synchronized
    fun flush() {
        try { codec?.flush() } catch (_: Exception) {}
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun release() {
        try {
            codec?.stop(); codec?.release()
        } catch (_: Exception) {}
        codec = null
        try {
            track?.pause(); track?.flush(); track?.release()
        } catch (_: Exception) {}
        track = null
        currentCt = -1
    }
}
