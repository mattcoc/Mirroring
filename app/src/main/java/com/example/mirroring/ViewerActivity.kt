package com.example.mirroring

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class ViewerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: LinearLayout
    private lateinit var progress: CircularProgressIndicator
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var actions: LinearLayout
    private val sender = Executors.newSingleThreadExecutor()
    private var surface: Surface? = null
    @Volatile
    private var session = 0
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var out: DataOutputStream? = null
    @Volatile
    private var videoW = 0
    @Volatile
    private var videoH = 0
    private val host by lazy { intent.getStringExtra(EXTRA_HOST).orEmpty() }
    private val name by lazy { intent.getStringExtra(EXTRA_NAME) ?: host }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        root = findViewById(R.id.root)
        surfaceView = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        actions = findViewById(R.id.actions)
        findViewById<View>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<View>(R.id.retryButton).setOnClickListener { connect() }

        WindowCompat.getInsetsController(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post { fitVideo() } }
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { v, e -> sendTouch(v, e); true }
    }

    override fun onDestroy() {
        disconnect()
        sender.shutdown()
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        connect()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
        disconnect()
    }

    private fun connect() {
        val s = surface ?: return
        disconnect()
        val id = session
        showStatus("Connessione a $name…", host, loading = true)
        thread { receive(id, s) }
    }

    private fun disconnect() {
        session++
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    private fun alive(id: Int) = id == session

    private fun receive(id: Int, surface: Surface) {
        var codec: MediaCodec? = null
        var connected = false
        var shown = false
        try {
            val s = Socket()
            socket = s
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, Protocol.PORT), 5000)
            s.soTimeout = 10_000
            if (!alive(id)) return
            connected = true
            out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            runOnUiThread { if (alive(id)) showStatus("In attesa del video…", name, loading = true) }
            videoW = input.readInt()
            videoH = input.readInt()
            runOnUiThread { fitVideo() }

            val c = MediaCodec.createDecoderByType(HostService.MIME)
            codec = c
            try {
                c.configure(decoderFormat(lowLatency = true), surface, null, 0)
            } catch (_: Exception) {
                c.reset()
                c.configure(decoderFormat(lowLatency = false), surface, null, 0)
            }
            c.start()
            val info = MediaCodec.BufferInfo()
            while (alive(id)) {
                val size = input.readInt()
                val flags = input.readInt()
                if (size == 0) continue
                val data = ByteArray(size)
                input.readFully(data)
                var i: Int
                do {
                    i = c.dequeueInputBuffer(10_000)
                    if (render(c, info) && !shown) {
                        shown = true
                        runOnUiThread { if (alive(id)) overlay.visibility = View.GONE }
                    }
                } while (i < 0 && alive(id))
                if (i < 0) break
                c.getInputBuffer(i)!!.apply { clear(); put(data) }
                c.queueInputBuffer(
                    i, 0, size, System.nanoTime() / 1000,
                    flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                )
                if (render(c, info) && !shown) {
                    shown = true
                    runOnUiThread { if (alive(id)) overlay.visibility = View.GONE }
                }
            }
        } catch (e: Exception) {
            if (alive(id)) runOnUiThread {
                if (!alive(id)) return@runOnUiThread
                if (connected) {
                    showError("Connessione interrotta", e.message ?: e.javaClass.simpleName)
                } else {
                    showError(
                        "Impossibile raggiungere $name",
                        "Controlla che sull'altro dispositivo la condivisione sia avviata e che siate sulla stessa rete Wi‑Fi.\n\n$host · ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun decoderFormat(lowLatency: Boolean) =
        MediaFormat.createVideoFormat(HostService.MIME, videoW, videoH).apply {
            if (lowLatency) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

    private fun render(c: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        var rendered = false
        var o = c.dequeueOutputBuffer(info, 0)
        while (o >= 0) {
            c.releaseOutputBuffer(o, true)
            rendered = true
            o = c.dequeueOutputBuffer(info, 0)
        }
        return rendered
    }

    private fun showStatus(title: String, detail: String, loading: Boolean) {
        overlay.visibility = View.VISIBLE
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        actions.visibility = if (loading) View.GONE else View.VISIBLE
        statusText.text = title
        detailText.text = detail
    }

    private fun showError(title: String, detail: String) = showStatus(title, detail, loading = false)

    private fun fitVideo() {
        if (videoW == 0 || root.width == 0) return
        val scale = minOf(root.width.toFloat() / videoW, root.height.toFloat() / videoH)
        val w = (videoW * scale).toInt()
        val h = (videoH * scale).toInt()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            surfaceView.layoutParams = lp
        }
    }

    private fun sendTouch(v: View, e: MotionEvent) {
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> Protocol.TOUCH_DOWN
            MotionEvent.ACTION_MOVE -> Protocol.TOUCH_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Protocol.TOUCH_UP
            else -> return
        }
        val x = e.x / v.width
        val y = e.y / v.height
        val o = out ?: return
        sender.execute {
            runCatching {
                o.writeByte(action)
                o.writeFloat(x)
                o.writeFloat(y)
                o.flush()
            }
        }
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_NAME = "name"
    }
}
