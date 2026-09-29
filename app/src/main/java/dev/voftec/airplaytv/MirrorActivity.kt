package dev.voftec.airplaytv

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class MirrorActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var idlePanel: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var instructionsText: TextView
    private lateinit var ipText: TextView
    private var videoWidth = 0
    private var videoHeight = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                AirPlayService.ACTION_MIRROR_START -> showMirror()
                AirPlayService.ACTION_MIRROR_STOP -> showIdle()
                AirPlayService.ACTION_STATUS -> {
                    attachToService()
                    refreshStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        hideSystemUi()
        attachToService()
        ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
        syncState(intent)
    }

    /** Pipelines live in the service; the activity only installs the
     *  aspect-ratio listener. The surface is attached strictly from
     *  SurfaceHolder.Callback. */
    private fun attachToService() {
        val svc = AirPlayService.instance ?: return
        svc.aspectListener = { w, h -> runOnUiThread { onVideoSize(w, h) } }
        if (::surfaceView.isInitialized && surfaceView.holder.surface.isValid) {
            svc.videoDecoder?.setSurface(surfaceView.holder.surface)
        }
    }

    private fun syncState(intent: Intent?) {
        val svc = AirPlayService.instance
        if (intent?.action == AirPlayService.ACTION_MIRROR_START ||
            svc?.isMirroring == true) {
            showMirror()
        } else {
            showIdle()
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    if (h.surface.isValid) {
                        AirPlayService.instance?.videoDecoder?.setSurface(h.surface)
                    }
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    AirPlayService.instance?.videoDecoder?.setSurface(null)
                }
            })
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        idlePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(64, 64, 64, 64)
        }
        titleText = TextView(this).apply {
            text = getString(R.string.idle_title)
            textSize = 42f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        instructionsText = TextView(this).apply {
            textSize = 26f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        ipText = TextView(this).apply {
            textSize = 22f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            textSize = 20f
            setTextColor(0xFF3DDC84.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }
        val renameButton = Button(this).apply {
            text = getString(R.string.rename_button)
            setOnClickListener { showRenameDialog() }
        }
        idlePanel.addView(titleText)
        idlePanel.addView(instructionsText)
        idlePanel.addView(ipText)
        idlePanel.addView(statusText)
        idlePanel.addView(renameButton)
        root.addView(idlePanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
        refreshStatus()
        showIdle()
    }

    private fun refreshStatus() {
        val name = Settings.deviceName(this)
        instructionsText.text = getString(R.string.idle_instructions, name)
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        ipText.text = getString(R.string.ip_label, ip)
        statusText.text = getString(
            if (AirPlayService.running) R.string.status_running else R.string.status_starting
        )
        statusText.setTextColor(
            if (AirPlayService.running) 0xFF3DDC84.toInt() else 0xFFFFBB33.toInt()
        )
    }

    private fun showMirror() {
        idlePanel.visibility = View.GONE
        applyLetterbox()
    }

    private fun showIdle() {
        idlePanel.visibility = View.VISIBLE
        refreshStatus()
    }

    override fun onDestroy() {
        AirPlayService.instance?.aspectListener = null
        super.onDestroy()
    }

    private fun onVideoSize(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        applyLetterbox()
    }

    private fun applyLetterbox() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val vw = surfaceView.width.toFloat().coerceAtLeast(1f)
        val vh = surfaceView.height.toFloat().coerceAtLeast(1f)
        val srcAspect = videoWidth.toFloat() / videoHeight
        val dstAspect = vw / vh
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (srcAspect > dstAspect) {
            lp.width = vw.toInt()
            lp.height = (vw / srcAspect).toInt()
        } else {
            lp.height = vh.toInt()
            lp.width = (vh * srcAspect).toInt()
        }
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
        surfaceView.holder.setFixedSize(videoWidth, videoHeight)
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(Settings.deviceName(this@MirrorActivity))
            hint = getString(R.string.rename_hint)
            requestFocus()
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.rename_title))
            .setView(input)
            .setPositiveButton(getString(R.string.rename_ok)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    Settings.setDeviceName(this, name)
                    AirPlayService.instance?.restartServer()
                    refreshStatus()
                }
            }
            .setNegativeButton(getString(R.string.rename_cancel), null)
            .show()
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        syncState(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        attachToService()
        refreshStatus()
        syncState(null)
        val filter = IntentFilter().apply {
            addAction(AirPlayService.ACTION_MIRROR_START)
            addAction(AirPlayService.ACTION_MIRROR_STOP)
            addAction(AirPlayService.ACTION_STATUS)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
