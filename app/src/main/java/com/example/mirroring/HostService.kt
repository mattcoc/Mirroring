package com.example.mirroring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class HostService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var server: ServerSocket? = null
    @Volatile
    private var running = false
    private var sizes = listOf<Pair<Int, Int>>()
    private var width = 0
    private var height = 0
    private var dpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (projection != null) return START_NOT_STICKY
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(intent!!.getIntExtra(EXTRA_CODE, 0), data)
                ?: throw IOException("Permesso di cattura non valido")
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopSelf()
            }, Handler(Looper.getMainLooper()))
            projection = p
            computeSizes()
            width = sizes[0].first
            height = sizes[0].second
            display = p.createVirtualDisplay(
                "mirroring", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null
            )
        } catch (e: Exception) {
            post("Errore: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        post(WAITING)
        thread { serve() }
        val name = Discovery.deviceName(this)
        thread { runCatching { Discovery.broadcastLoop(name) { running } } }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        display?.release()
        projection?.stop()
        post(null)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Condivisione schermo", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, HostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Condivisione schermo attiva")
            .setContentText("IP: " + Protocol.localIps().joinToString())
            .setContentIntent(open)
            .addAction(0, "Interrompi", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, 1, notification, type)
    }

    private fun computeSizes() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        dpi = m.densityDpi
        val caps = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } }
                ?.getCapabilitiesForType(MIME)?.videoCapabilities
        }.getOrNull()
        val all = intArrayOf(1920, 1600, 1280, 1024, 800, 640).map { max ->
            val scale = minOf(1f, max.toFloat() / maxOf(m.widthPixels, m.heightPixels))
            ((m.widthPixels * scale).toInt() / 16 * 16) to ((m.heightPixels * scale).toInt() / 16 * 16)
        }.distinct()
        val supported = all.filter { caps == null || caps.isSizeSupported(it.first, it.second) }
        sizes = supported + (all - supported.toSet())
    }

    private fun createEncoder(): Pair<MediaCodec, Surface> {
        var last: Exception? = null
        for ((w, h) in sizes) {
            val codec = MediaCodec.createEncoderByType(MIME)
            try {
                val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                if (w != width || h != height) {
                    width = w
                    height = h
                    display?.resize(w, h, dpi)
                }
                return codec to surface
            } catch (e: Exception) {
                codec.release()
                last = e
            }
        }
        throw last ?: IOException("Encoder video non disponibile")
    }

    private fun serve() {
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(Protocol.PORT))
            }
        } catch (e: IOException) {
            post("Errore: porta ${Protocol.PORT} non disponibile")
            return
        }
        server = ss
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                break
            }
            post("Connesso a ${socket.inetAddress.hostAddress}")
            try {
                stream(socket)
            } catch (_: Exception) {
            } finally {
                runCatching { socket.close() }
            }
            if (running) post(WAITING)
        }
    }

    private fun stream(socket: Socket) {
        socket.tcpNoDelay = true
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 1 shl 16))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val (codec, surface) = createEncoder()
        try {
            out.writeInt(width)
            out.writeInt(height)
            out.flush()
            thread {
                try {
                    while (true) {
                        val action = input.readByte().toInt()
                        val x = input.readFloat()
                        val y = input.readFloat()
                        TouchService.instance?.onTouch(action, x, y)
                    }
                } catch (_: IOException) {
                    runCatching { socket.close() }
                }
            }
            display?.surface = surface
            val info = MediaCodec.BufferInfo()
            var lastSent = SystemClock.elapsedRealtime()
            while (running && !socket.isClosed) {
                val i = codec.dequeueOutputBuffer(info, 20_000)
                if (i < 0) {
                    if (SystemClock.elapsedRealtime() - lastSent > 1000) {
                        out.writeInt(0)
                        out.writeInt(0)
                        out.flush()
                        lastSent = SystemClock.elapsedRealtime()
                    }
                    continue
                }
                val buf = codec.getOutputBuffer(i)!!
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(bytes)
                codec.releaseOutputBuffer(i, false)
                out.writeInt(info.size)
                out.writeInt(info.flags)
                out.write(bytes)
                out.flush()
                lastSent = SystemClock.elapsedRealtime()
            }
        } finally {
            display?.surface = null
            runCatching { codec.stop() }
            codec.release()
            surface.release()
        }
    }

    companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "mirroring"
        private const val WAITING = "Attivo, in attesa di connessione"
        private val main = Handler(Looper.getMainLooper())

        @Volatile
        var status: String? = null
            private set
        var onStatus: (() -> Unit)? = null

        private fun post(s: String?) {
            status = s
            main.post { onStatus?.invoke() }
        }
    }
}
