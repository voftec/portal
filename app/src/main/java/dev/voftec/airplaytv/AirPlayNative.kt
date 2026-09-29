package dev.voftec.airplaytv

import android.util.Log
import java.nio.ByteBuffer

/** JNI bridge to the vendored UxPlay AirPlay server. */
object AirPlayNative {

    init {
        System.loadLibrary("airplaytv")
    }

    interface Listener {
        fun onConnectionOpen()
        fun onConnectionClose()
        fun onConnectionReset(reason: Int)
        fun onMirrorStart()
        /** Annex-B H.264 access unit (SPS/PPS prepended before IDRs). */
        fun onVideoData(data: ByteBuffer, len: Int, nalCount: Int)
        fun onVideoFlush()
        fun onVideoReset(type: Int)
        fun onVideoSize(widthSource: Float, heightSource: Float, width: Float, height: Float)
        fun onAudioFormat(ct: Int, spf: Int)
        fun onAudioData(data: ByteBuffer, len: Int, ct: Int, seqnum: Int)
        fun onAudioFlush()
        fun onAudioVolume(db: Float)
        fun onClientRequest(name: String)
    }

    external fun nativeStart(
        name: String,
        hwAddr: ByteArray,
        keyfilePath: String,
        listener: Listener,
    ): Boolean

    external fun nativeStop()
    external fun nativeIsRunning(): Boolean
}
