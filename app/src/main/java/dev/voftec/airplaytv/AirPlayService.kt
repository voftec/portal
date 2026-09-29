package dev.voftec.airplaytv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.security.SecureRandom

class AirPlayService : Service(), AirPlayNative.Listener {

    companion object {
        private const val TAG = "AirPlayTV"
        private const val CHANNEL_ID = "airplay"
        private const val NOTIF_ID = 1
        const val ACTION_MIRROR_START = "dev.voftec.airplaytv.MIRROR_START"
        const val ACTION_MIRROR_STOP = "dev.voftec.airplaytv.MIRROR_STOP"
        const val ACTION_STATUS = "dev.voftec.airplaytv.STATUS"

        @Volatile var running = false
            private set
        @Volatile var instance: AirPlayService? = null
            private set
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mirroring = false

    // Owned by MirrorActivity via the binder-free singleton hand-off.
    var videoDecoder: VideoDecoder? = null
    var audioPipeline: AudioPipeline? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        acquireLocks()
        startServer()
        return START_STICKY
    }

    private fun deviceName(): String =
        Settings.deviceName(this)

    private fun startServer() {
        if (running) return
        val name = deviceName()
        val keyfile = filesDir.resolve("airplay.pem").absolutePath
        val ok = AirPlayNative.nativeStart(name, Settings.hwAddr(this), keyfile, this)
        running = ok
        Log.i(TAG, "nativeStart(\"$name\") -> $ok")
        broadcastStatus()
    }

    fun restartServer() {
        if (!running) { startServer(); return }
        AirPlayNative.nativeStop()
        running = false
        startServer()
    }

    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (multicastLock == null) {
            multicastLock = wm.createMulticastLock("airplay-mdnsd").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "airplay").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplaytv:server").apply {
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try { multicastLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        multicastLock = null; wifiLock = null; wakeLock = null
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MirrorActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, deviceName()))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun broadcastStatus() {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    private fun bringActivityToFront() {
        val i = Intent(this, MirrorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .setAction(ACTION_MIRROR_START)
        try {
            startActivity(i)
        } catch (e: Exception) {
            // SYSTEM_ALERT_WINDOW fallback path
            Log.w(TAG, "startActivity from service: $e")
        }
        sendBroadcast(Intent(ACTION_MIRROR_START).setPackage(packageName))
    }

    // ---- AirPlayNative.Listener (called from native threads) ----

    override fun onConnectionOpen() {}

    override fun onConnectionClose() {
        mirroring = false
        sendBroadcast(Intent(ACTION_MIRROR_STOP).setPackage(packageName))
    }

    override fun onConnectionReset(reason: Int) {
        mirroring = false
        sendBroadcast(Intent(ACTION_MIRROR_STOP).setPackage(packageName))
    }

    override fun onMirrorStart() {
        if (!mirroring) {
            mirroring = true
            bringActivityToFront()
        }
    }

    override fun onClientRequest(name: String) {
        Log.i(TAG, "conexión de $name")
    }

    override fun onVideoData(data: ByteBuffer, len: Int, nalCount: Int) {
        videoDecoder?.feed(data, len)
    }

    override fun onVideoFlush() {}

    override fun onVideoReset(type: Int) {
        mirroring = false
        videoDecoder?.reset()
        sendBroadcast(Intent(ACTION_MIRROR_STOP).setPackage(packageName))
    }

    override fun onVideoSize(widthSource: Float, heightSource: Float, width: Float, height: Float) {
        videoDecoder?.onReportedSize(width.toInt(), height.toInt())
    }

    override fun onAudioFormat(ct: Int, spf: Int) {
        audioPipeline?.onFormat(ct, spf)
    }

    override fun onAudioData(data: ByteBuffer, len: Int, ct: Int, seqnum: Int) {
        if (ct == 2) return // ALAC: fuera de alcance en v1
        audioPipeline?.feed(data, len)
    }

    override fun onAudioFlush() {
        audioPipeline?.flush()
    }

    override fun onAudioVolume(db: Float) {
        audioPipeline?.setVolume(db)
    }

    override fun onDestroy() {
        running = false
        AirPlayNative.nativeStop()
        videoDecoder?.release()
        audioPipeline?.release()
        releaseLocks()
        instance = null
        super.onDestroy()
    }
}
