package dev.booxinkbridge.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Phase 0 spike: an overlay window with Onyx raw drawing, driven over adb.
 *
 *   am start-foreground-service -n dev.booxinkbridge.spike/.SpikeService -a ACTION [extras]
 *
 * SHOW  --ei x --ei y --ei w --ei h   put the overlay there (screen px) and start raw drawing
 * HIDE                                 remove the overlay
 * RAW   --ez on                        raw drawing on or off, overlay left in place
 * FLASH                                raw drawing off then on: lets the app underneath repaint
 * PENUP --ez on --ei ms                 let the screen refresh that long after the pen lifts
 * POST  --ez on                        whether pen events are also posted to the app underneath
 * AUTO  --ez on                        raw drawing off at pen-up refresh, back on when the pen is in range
 * TOUCH --ez on                        make the overlay window touchable or not
 * LIMIT --ei x --ei y --ei w --ei h [--ei ex --ei ey --ei ew --ei eh]
 *                                      change the limit rect (overlay-local px), optional exclusion
 */
class SpikeService : Service() {

    private var view: SurfaceView? = null
    private var params: WindowManager.LayoutParams? = null
    private var helper: TouchHelper? = null
    private var points = 0
    private var beganAt = 0L
    private var auto = false
    private var rawOn = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("spike", "Ink bridge spike", NotificationManager.IMPORTANCE_LOW)
        )
        val note = Notification.Builder(this, "spike")
            .setContentTitle("Ink bridge spike")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, note)
        }
        Log.i(TAG, "service up")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        try {
            when (i.action) {
                "SHOW" -> show(i.getIntExtra("x", 0), i.getIntExtra("y", 300), i.getIntExtra("w", 1536), i.getIntExtra("h", 1000))
                "HIDE" -> hide()
                "RAW" -> {
                    val on = i.getBooleanExtra("on", true)
                    helper?.setRawDrawingEnabled(on)
                    Log.i(TAG, "raw drawing enabled=$on")
                }
                "FLASH" -> {
                    val t = System.nanoTime()
                    helper?.setRawDrawingEnabled(false)
                    helper?.setRawDrawingEnabled(true)
                    Log.i(TAG, "flash took ${(System.nanoTime() - t) / 1_000_000.0} ms")
                }
                "PENUP" -> {
                    val on = i.getBooleanExtra("on", true)
                    val ms = i.getIntExtra("ms", 500)
                    helper?.setPenUpRefreshTimeMs(ms)
                    helper?.setPenUpRefreshEnabled(on)
                    Log.i(TAG, "pen-up refresh enabled=$on after ${ms} ms")
                }
                "POST" -> {
                    val on = i.getBooleanExtra("on", true)
                    helper?.setPostInputEvent(on)
                    Log.i(TAG, "post input events to the app underneath=$on")
                }
                "AUTO" -> {
                    auto = i.getBooleanExtra("on", true)
                    Log.i(TAG, "auto release/retake=$auto")
                }
                "TOUCH" -> touchable(i.getBooleanExtra("on", false))
                "LIMIT" -> limit(i)
                else -> Log.i(TAG, "started, no action")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "action ${i.action} failed", t)
        }
        return START_NOT_STICKY
    }

    private fun show(x: Int, y: Int, w: Int, h: Int) {
        hide()
        val wm = getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y
        val sv = SurfaceView(this)
        sv.setZOrderOnTop(true)
        sv.holder.setFormat(PixelFormat.TRANSPARENT)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val loc = IntArray(2)
                sv.getLocationOnScreen(loc)
                Log.i(TAG, "surface created ${sv.width}x${sv.height} at screen ${loc[0]},${loc[1]}")
                attach(sv)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "surface changed ${width}x$height")
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "surface destroyed")
            }
        })
        wm.addView(sv, lp)
        view = sv
        params = lp
        Log.i(TAG, "overlay added at $x,$y ${w}x$h")
    }

    private fun attach(sv: SurfaceView) {
        try {
            helper = TouchHelper.create(sv, callback).apply {
                setStrokeWidth(4f)
                setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
                setStrokeColor(Color.BLACK)
                setLimitRect(Rect(0, 0, sv.width, sv.height), emptyList())
                openRawDrawing()
                setRawDrawingEnabled(true)
            }
            Log.i(TAG, "raw drawing open on overlay")
        } catch (t: Throwable) {
            Log.e(TAG, "attach failed", t)
        }
    }

    private fun hide() {
        try {
            helper?.setRawDrawingEnabled(false)
            helper?.closeRawDrawing()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed: ${t.message}")
        }
        helper = null
        view?.let { getSystemService(WindowManager::class.java).removeView(it) }
        view = null
        params = null
        Log.i(TAG, "overlay removed")
    }

    private fun touchable(on: Boolean) {
        val lp = params ?: return
        val sv = view ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        getSystemService(WindowManager::class.java).updateViewLayout(sv, lp)
        Log.i(TAG, "overlay touchable=$on")
    }

    private fun limit(i: Intent) {
        val sv = view ?: return
        val r = Rect(
            i.getIntExtra("x", 0), i.getIntExtra("y", 0),
            i.getIntExtra("x", 0) + i.getIntExtra("w", sv.width),
            i.getIntExtra("y", 0) + i.getIntExtra("h", sv.height),
        )
        val ex = if (i.hasExtra("ew")) listOf(
            Rect(
                i.getIntExtra("ex", 0), i.getIntExtra("ey", 0),
                i.getIntExtra("ex", 0) + i.getIntExtra("ew", 0),
                i.getIntExtra("ey", 0) + i.getIntExtra("eh", 0),
            )
        ) else emptyList()
        val t = System.nanoTime()
        helper?.setRawDrawingEnabled(false)
        helper?.setLimitRect(r, ex)
        helper?.setRawDrawingEnabled(true)
        Log.i(TAG, "limit=$r exclude=$ex took ${(System.nanoTime() - t) / 1_000_000.0} ms")
    }

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, tp: TouchPoint) {
            points = 1
            if (auto && !rawOn) Log.w(TAG, "stroke began while firmware render was off")
            beganAt = System.currentTimeMillis()
            Log.i(TAG, "stroke begin x=${tp.x} y=${tp.y} p=${tp.pressure} ts=${tp.timestamp} thread=${Thread.currentThread().name}")
        }
        override fun onRawDrawingTouchPointMoveReceived(tp: TouchPoint) { points++ }
        override fun onRawDrawingTouchPointListReceived(tpl: TouchPointList) {
            Log.i(TAG, "stroke list size=${tpl.size()}")
        }
        override fun onEndRawDrawing(b: Boolean, tp: TouchPoint) {
            Log.i(TAG, "stroke end x=${tp.x} y=${tp.y} points=$points ms=${System.currentTimeMillis() - beganAt}")
        }
        override fun onPenUpRefresh(refreshRect: android.graphics.RectF) {
            Log.i(TAG, "pen-up refresh rect=$refreshRect auto=$auto")
            if (auto) {
                // The pen has been away long enough: give the screen back to the app underneath.
                // Render off only: the SDK leaves scribble mode but keeps reading the pen,
                // so the hover callback still fires and can take the screen back.
                helper?.setRawDrawingRenderEnabled(false)
                rawOn = false
                Log.i(TAG, "auto: firmware render off")
            }
        }
        override fun onPenActive(tp: TouchPoint) {
            if (auto && !rawOn) {
                // The pen is back in range: take the screen before it touches down.
                val t = System.nanoTime()
                helper?.setRawDrawingRenderEnabled(true)
                rawOn = true
                Log.i(TAG, "auto: firmware render on, took ${(System.nanoTime() - t) / 1_000_000.0} ms")
            }
        }
        override fun onBeginRawErasing(b: Boolean, tp: TouchPoint) { Log.i(TAG, "erase begin x=${tp.x} y=${tp.y}") }
        override fun onEndRawErasing(b: Boolean, tp: TouchPoint) { Log.i(TAG, "erase end") }
        override fun onRawErasingTouchPointMoveReceived(tp: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(tpl: TouchPointList) {}
    }

    override fun onDestroy() {
        hide()
        super.onDestroy()
    }

    companion object { private const val TAG = "InkSpike" }
}
