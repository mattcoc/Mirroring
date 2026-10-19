package com.example.mirroring

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class TouchService : AccessibilityService() {

    private val lock = Any()
    private var path: Path? = null
    private var startTime = 0L
    private var stroke: GestureDescription.StrokeDescription? = null
    private var last: PointF? = null
    private var pending: PointF? = null
    private var upPending = false
    private var busy = false

    private val callback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
        override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun onTouch(action: Int, nx: Float, ny: Float) {
        val (w, h) = screenSize()
        val x = (nx * w).coerceIn(0f, w - 1f)
        val y = (ny * h).coerceIn(0f, h - 1f)
        if (Build.VERSION.SDK_INT >= 26) live(action, x, y) else buffered(action, x, y)
    }

    private fun live(action: Int, x: Float, y: Float) = synchronized(lock) {
        when (action) {
            Protocol.TOUCH_DOWN -> {
                pending = null
                upPending = false
                start(x, y)
            }
            Protocol.TOUCH_MOVE -> {
                pending = PointF(x, y)
                if (!busy) next()
            }
            Protocol.TOUCH_UP -> {
                pending = PointF(x, y)
                upPending = true
                if (!busy) next()
            }
        }
    }

    private fun start(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < 26) return
        val s = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 1, true)
        stroke = s
        last = PointF(x, y)
        send(s)
    }

    private fun next() {
        if (Build.VERSION.SDK_INT < 26) return
        val target = pending ?: (if (upPending) last else null) ?: return
        pending = null
        val from = last
        val s = stroke
        if (s == null || from == null) {
            if (!upPending) start(target.x, target.y)
            return
        }
        val end = upPending
        upPending = false
        val p = Path().apply {
            moveTo(from.x, from.y)
            lineTo(target.x, target.y)
        }
        val c = s.continueStroke(p, 0, 16, !end)
        stroke = if (end) null else c
        last = target
        send(c)
    }

    private fun send(s: GestureDescription.StrokeDescription) {
        busy = true
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), callback, null)
    }

    private fun done(completed: Boolean) = synchronized(lock) {
        busy = false
        if (!completed) stroke = null
        if (pending != null || upPending) next()
    }

    private fun buffered(action: Int, x: Float, y: Float) {
        when (action) {
            Protocol.TOUCH_DOWN -> {
                path = Path().apply { moveTo(x, y) }
                startTime = SystemClock.uptimeMillis()
            }
            Protocol.TOUCH_MOVE -> path?.lineTo(x, y)
            Protocol.TOUCH_UP -> {
                val p = path ?: return
                p.lineTo(x, y)
                path = null
                val duration = (SystemClock.uptimeMillis() - startTime)
                    .coerceIn(1L, GestureDescription.getMaxGestureDuration())
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(p, 0, duration))
                        .build(),
                    null, null
                )
            }
        }
    }

    private fun screenSize(): Pair<Float, Float> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width().toFloat() to b.height().toFloat()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels.toFloat() to m.heightPixels.toFloat()
        }
    }

    companion object {
        @Volatile
        var instance: TouchService? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains("${context.packageName}/${TouchService::class.java.name}")
        }
    }
}
