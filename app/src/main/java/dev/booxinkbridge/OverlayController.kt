package dev.booxinkbridge

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * The overlay window and the Onyx pen pipeline on it. Main thread only.
 *
 * WHO OWNS THE SCREEN. While the firmware renders pen ink, nothing else on the panel
 * repaints. So the firmware gets the screen only while the pen is about: it is taken when
 * the pen comes into range (`onPenActive`) and given back a short time after the pen lifts
 * (`onPenUpRefresh`) - later if the pen is still hovering, since the next stroke would take
 * it straight back - or when the pen hovered in and left without touching (the watchdog).
 * Render is switched with `setRawDrawingRenderEnabled`, never `setRawDrawingEnabled`: the
 * latter also stops the SDK reading the pen, and then nothing reports the pen coming back.
 */
class OverlayController(
    private val context: Context,
    private val sink: Sink,
) {
    interface Sink {
        fun strokeBegin(id: Int, rev: Int)
        fun stroke(id: Int, rev: Int, points: List<WirePoint>)
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var view: SurfaceView? = null
    private var params: WindowManager.LayoutParams? = null
    private var helper: TouchHelper? = null

    /** What the plugin last asked for, in screen px. */
    private var wantRect: PxRect? = null
    private var wantExclude: List<PxRect> = emptyList()
    /** The limit and exclusion last handed to the SDK, so an unchanged surface costs nothing. */
    private var appliedKey: String? = null
    private var geometry: Geometry? = null
    private var rev = 0

    private var paused = false
    private var renderOn = false
    private var strokeActive = false
    private var lastPenAt = 0L

    private var color = Color.BLACK
    private var widthCss = 2.0
    var penUpDelayMs = 400
        set(value) {
            field = value
            try { helper?.setPenUpRefreshTimeMs(value) } catch (t: Throwable) { Log.w(TAG, "delay: ${t.message}") }
        }
    /** The user's trim on the line's width, in percent. */
    var previewTrimPercent = 100
        set(value) {
            field = value
            applyWidth()
        }
    /**
     * How the written area is refreshed after the firmware lets go: 0 not at all, 1 the SDK's
     * handwriting repaint, then region refreshes in 2 GC, 3 REGAL, 4 DU, 5 GU, 6 GU_FAST.
     * DU is the default: on a Note Mini C it made the swap without the heavy flash of 1.
     */
    var repaintMode = 4
    /** When a release was put off because the pen was still near; 0 when none is waiting. */
    @Volatile private var waitingSince = 0L
    /** Repaint the written area after every release, not only one a pen-down cut short. */
    var repaintAlways = true
    /** Bounds, in overlay px, of what has been written since the last repaint. */
    private val written = Rect()
    /** What was written before the last release, and when that release was. */
    private val released = Rect()
    @Volatile private var releasedAt = 0L
    /** A pen-down interrupted the panel's own refresh after a release, so the next release repaints. */
    @Volatile private var cutShort = false
    /** One of TouchHelper's STROKE_STYLE_ values. */
    var strokeStyle = TouchHelper.STROKE_STYLE_PENCIL
        set(value) {
            field = value
            try { helper?.setStrokeStyle(value) } catch (t: Throwable) { Log.w(TAG, "style: ${t.message}") }
        }
    var originX = 0
    var originY = 0

    // The stroke being written, mapped with the geometry in force at pen-down.
    private var nextId = 0
    private var strokeId = 0
    private var strokeRev = 0
    private var strokeGeometry: Geometry? = null
    private var strokeOffsetX = 0
    private var strokeOffsetY = 0
    private var strokeT0 = 0L
    private var strokeClock0 = 0L
    private var dropping = false
    private val points = ArrayList<WirePoint>()
    private val maxPressure: Float by lazy {
        try { EpdController.getMaxTouchPressure().takeIf { it > 0f } ?: 4096f } catch (t: Throwable) { 4096f }
    }

    val hasOverlay: Boolean get() = view != null

    // ---- frames from the plugin ------------------------------------------------

    fun surface(s: Inbound.Surface) {
        val screenW = wm.currentWindowMetrics.bounds.width()
        val g = Geometry(screenW, s.viewportW, originX, originY)
        val rect = g.toScreen(s.rect)
        geometry = g
        rev = s.rev
        if (rect.w <= 0 || rect.h <= 0) {
            Log.i(TAG, "surface rev=${s.rev} is empty: overlay removed")
            removeOverlay()
            return
        }
        wantRect = rect
        wantExclude = s.exclude.mapNotNull { g.localTo(rect, g.toScreen(it)) }
        applyWidth()
        val lp = params
        val sv = view
        if (sv == null || lp == null) {
            addOverlay(rect)
            return
        }
        if (lp.x != rect.x || lp.y != rect.y || lp.width != rect.w || lp.height != rect.h) {
            lp.x = rect.x
            lp.y = rect.y
            lp.width = rect.w
            lp.height = rect.h
            wm.updateViewLayout(sv, lp)
        }
        // The window moves on a later frame; the limit is in view-local px resolved against
        // the view's place on screen, so it is applied once the view has got there.
        applyLimitWhenSettled(0)
    }

    fun pen(p: Inbound.Pen) {
        Protocol.parseColor(p.color)?.let { color = it }
        widthCss = p.width
        try { helper?.setStrokeColor(color) } catch (t: Throwable) { Log.w(TAG, "color: ${t.message}") }
        applyWidth()
    }

    fun pause() {
        paused = true
        setRender(false, "pause")
    }

    fun resume() {
        paused = false
        // Render stays off until the pen is seen: nothing to draw until then.
    }

    /** `close` from the plugin, or the plugin went away. */
    fun close() {
        removeOverlay()
        paused = false
    }

    // ---- the overlay window -----------------------------------------------------

    private fun addOverlay(rect: PxRect) {
        val lp = WindowManager.LayoutParams(
            rect.w, rect.h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = rect.x
        lp.y = rect.y
        val sv = SurfaceView(context)
        sv.setZOrderOnTop(true)
        sv.holder.setFormat(PixelFormat.TRANSPARENT)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (view === sv && helper == null) attach(sv)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (view === sv && helper != null) applyLimitWhenSettled(0)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        view = sv
        params = lp
        appliedKey = null
        wm.addView(sv, lp)
        Log.i(TAG, "overlay added at ${rect.x},${rect.y} ${rect.w}x${rect.h}")
    }

    private fun attach(sv: SurfaceView) {
        try {
            helper = TouchHelper.create(sv, callback).apply {
                setStrokeStyle(strokeStyle)
                setStrokeColor(color)
                setStrokeWidth(strokeWidthPx())
                setLimitRect(Rect(0, 0, sv.width, sv.height), wantExclude.map { it.toRect() })
                setPenUpRefreshTimeMs(penUpDelayMs)
                setPenUpRefreshEnabled(true)
                openRawDrawing()
                setRawDrawingEnabled(true)
            }
            appliedKey = limitKey(sv)
            // Opened with the screen given back: the app underneath keeps repainting until the pen shows up.
            renderOn = true
            setRender(false, "attach")
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, WATCHDOG_TICK_MS)
            Log.i(TAG, "raw drawing open on overlay ${sv.width}x${sv.height}")
        } catch (t: Throwable) {
            Log.e(TAG, "attach failed", t)
            helper = null
        }
    }

    private fun limitKey(sv: SurfaceView): String {
        val loc = IntArray(2)
        sv.getLocationOnScreen(loc)
        return "${loc[0]},${loc[1]},${sv.width},${sv.height}|$wantExclude"
    }

    /** Hand the SDK the limit and exclusion once the view sits where the plugin asked, retrying briefly while it moves. */
    private fun applyLimitWhenSettled(attempt: Int) {
        val sv = view ?: return
        val h = helper ?: return
        val want = wantRect ?: return
        val loc = IntArray(2)
        sv.getLocationOnScreen(loc)
        val settled = loc[0] == want.x && loc[1] == want.y && sv.width == want.w && sv.height == want.h
        if (!settled && attempt < 10) {
            main.postDelayed({ applyLimitWhenSettled(attempt + 1) }, 30)
            return
        }
        if (strokeActive) {
            // Never pull the limit out from under a stroke; the next surface or lift retries.
            main.postDelayed({ applyLimitWhenSettled(0) }, 100)
            return
        }
        val key = limitKey(sv)
        if (key == appliedKey) return
        try {
            h.setRawDrawingEnabled(false)
            h.setLimitRect(Rect(0, 0, sv.width, sv.height), wantExclude.map { it.toRect() })
            h.setRawDrawingEnabled(true)
            appliedKey = key
            // Re-enabling raw drawing resumes the firmware render; put it back where it was.
            val was = renderOn
            renderOn = true
            if (!was) setRender(false, "limit")
            Log.i(TAG, "limit applied: $key settled=$settled")
        } catch (t: Throwable) {
            Log.w(TAG, "limit failed: ${t.message}")
        }
    }

    private fun removeOverlay() {
        main.removeCallbacks(watchdog)
        try {
            helper?.setRawDrawingEnabled(false)
            helper?.closeRawDrawing()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed: ${t.message}")
        }
        helper = null
        view?.let {
            try { wm.removeView(it) } catch (t: Throwable) { Log.w(TAG, "removeView: ${t.message}") }
            Log.i(TAG, "overlay removed")
        }
        view = null
        params = null
        wantRect = null
        appliedKey = null
        renderOn = false
        strokeActive = false
        dropping = false
        points.clear()
    }

    private fun strokeWidthPx(): Float = previewWidthPx(widthCss, geometry?.scale ?: 1.0, previewTrimPercent)

    private fun applyWidth() {
        val px = strokeWidthPx()
        try { helper?.setStrokeWidth(px) } catch (t: Throwable) { Log.w(TAG, "width: ${t.message}") }
        if (px != loggedWidthPx) {
            loggedWidthPx = px
            Log.i(TAG, "preview width ${px}px (css=$widthCss trim=$previewTrimPercent% style=$strokeStyle)")
        }
    }
    private var loggedWidthPx = -1f

    private fun setRender(on: Boolean, why: String) {
        if (renderOn == on) return
        val h = helper ?: return
        try {
            h.setRawDrawingRenderEnabled(on)
            renderOn = on
            Log.i(TAG, "firmware render ${if (on) "on" else "off"} ($why)")
        } catch (t: Throwable) {
            Log.w(TAG, "render $on failed: ${t.message}")
        }
    }

    /**
     * Ask the panel to show what the app underneath has drawn where the pen wrote.
     *
     * Leaving scribble mode does not do that by itself. The firmware's ink stays on the
     * panel until the app next changes those pixels, and the app drew its stroke while the
     * screen was frozen, so often nothing prompts it: the firmware's line sat there until a
     * scroll, and then visibly changed weight. Found on a Note Mini C by reading the
     * WebView's canvases while the panel showed something else.
     */
    private fun repaintWritten() {
        val sv = view ?: return
        if (renderOn || strokeActive) return
        cutShort = false
        if (synchronized(written) { written.isEmpty }) return
        val pad = (strokeWidthPx() * 2).toInt() + REPAINT_PAD_PX
        val r = synchronized(written) { Rect(written).also { written.setEmpty() } }
        r.inset(-pad, -pad)
        if (!r.intersect(0, 0, sv.width, sv.height)) return
        try {
            when (repaintMode) {
                1 -> EpdController.handwritingRepaint(sv, r)
                2 -> EpdController.refreshScreenRegion(sv, r.left, r.top, r.width(), r.height(), UpdateMode.GC)
                3 -> EpdController.refreshScreenRegion(sv, r.left, r.top, r.width(), r.height(), UpdateMode.REGAL)
                4 -> EpdController.refreshScreenRegion(sv, r.left, r.top, r.width(), r.height(), UpdateMode.DU)
                5 -> EpdController.refreshScreenRegion(sv, r.left, r.top, r.width(), r.height(), UpdateMode.GU)
                6 -> EpdController.refreshScreenRegion(sv, r.left, r.top, r.width(), r.height(), UpdateMode.GU_FAST)
                else -> return
            }
            Log.i(TAG, "repaint mode=$repaintMode $r")
        } catch (t: Throwable) {
            Log.w(TAG, "repaint failed: ${t.message}")
        }
    }

    /** Give the screen back to the app underneath. */
    private fun release(why: String) {
        waitingSince = 0L
        if (!renderOn) return
        setRender(false, why)
        releasedAt = SystemClock.uptimeMillis()
        if (cutShort || repaintAlways) {
            main.postDelayed({ repaintWritten() }, if (repaintAlways) REPAINT_SOON_MS else RELEASE_HOLD_MS)
        } else synchronized(written) {
            // The panel refreshes this by itself. Kept in case the next pen-down cuts that short.
            released.set(written)
            written.setEmpty()
        }
    }

    /** A release put off because the pen was still near: made once the pen has gone, or has hovered long enough. */
    private val releaseWhenClear = object : Runnable {
        override fun run() {
            val since = waitingSince
            if (since == 0L || helper == null || strokeActive) return
            val now = SystemClock.uptimeMillis()
            when {
                now - lastPenAt >= PEN_NEAR_MS -> release("pen up, pen gone")
                now - since >= HOVER_HOLD_MAX_MS -> release("pen up, still hovering")
                else -> main.postDelayed(this, RELEASE_TICK_MS)
            }
        }
    }

    /** The pen hovered in and left without touching: no pen-up refresh will come, so give the screen back here. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (helper == null) return
            if (renderOn && !strokeActive && SystemClock.uptimeMillis() - lastPenAt > HOVER_IDLE_MS) {
                setRender(false, "pen left")
            }
            main.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    // ---- the pen ---------------------------------------------------------------

    private fun wirePoint(tp: TouchPoint): WirePoint {
        synchronized(written) {
            val x = tp.x.toInt()
            val y = tp.y.toInt()
            // Rect.union(x, y) on an empty rect would stretch it from the origin.
            if (written.isEmpty) written.set(x, y, x + 1, y + 1) else written.union(x, y)
        }
        val g = strokeGeometry!!
        val t = if (strokeT0 > 0 && tp.timestamp > 0) tp.timestamp - strokeT0 else SystemClock.uptimeMillis() - strokeClock0
        return WirePoint(
            g.cssX(tp.x.toDouble() + strokeOffsetX),
            g.cssY(tp.y.toDouble() + strokeOffsetY),
            (tp.pressure / maxPressure).coerceIn(0f, 1f).toDouble(),
            maxOf(0L, t),
        )
    }

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, tp: TouchPoint) {
            lastPenAt = SystemClock.uptimeMillis()
            waitingSince = 0L
            if (lastPenAt - releasedAt < RELEASE_HOLD_MS) synchronized(written) {
                // The pen came down while the panel was still refreshing the last release: that
                // area may be left half drawn, so it is repainted with what is written next.
                cutShort = true
                if (!released.isEmpty) { if (written.isEmpty) written.set(released) else written.union(released) }
            }
            strokeActive = true
            points.clear()
            val g = geometry
            val lp = params
            dropping = paused || g == null || lp == null
            if (dropping) {
                Log.i(TAG, "stroke dropped at pen-down (paused=$paused)")
                return
            }
            // A cold strike with no hover first: take the screen now rather than not at all.
            setRender(true, "pen down")
            strokeId = ++nextId
            strokeRev = rev
            strokeGeometry = g
            strokeOffsetX = lp!!.x
            strokeOffsetY = lp.y
            strokeT0 = tp.timestamp
            strokeClock0 = lastPenAt
            sink.strokeBegin(strokeId, strokeRev)
            points.add(wirePoint(tp))
        }

        override fun onRawDrawingTouchPointMoveReceived(tp: TouchPoint) {
            lastPenAt = SystemClock.uptimeMillis()
            if (!dropping && strokeActive) points.add(wirePoint(tp))
        }

        // The same points again as one list at the lift; the moves above already have them.
        override fun onRawDrawingTouchPointListReceived(tpl: TouchPointList) {}

        override fun onEndRawDrawing(b: Boolean, tp: TouchPoint) {
            lastPenAt = SystemClock.uptimeMillis()
            if (!strokeActive) return
            strokeActive = false
            if (dropping) {
                dropping = false
                return
            }
            val end = wirePoint(tp)
            val last = points.lastOrNull()
            if (last == null || last.x != end.x || last.y != end.y) points.add(end)
            // The top pressure as sent, and the digitizer's scale it was divided by: what to
            // compare with the pressure the WebView reports for the same push.
            Log.i(TAG, "stroke $strokeId: ${points.size} points, top pressure ${"%.3f".format(points.maxOf { it.pressure })} of raw max $maxPressure")
            sink.stroke(strokeId, strokeRev, ArrayList(points))
            points.clear()
        }

        override fun onPenActive(tp: TouchPoint) {
            lastPenAt = SystemClock.uptimeMillis()
            // Not straight after a release: the panel is still showing the app's own strokes in
            // place of the firmware's, and taking the screen back mid-refresh freezes them half
            // drawn. A pen that touches down in that time takes the screen anyway (pen down).
            if (!renderOn && !paused && lastPenAt - releasedAt >= RELEASE_HOLD_MS) setRender(true, "pen in range")
        }

        override fun onPenUpRefresh(refreshRect: RectF) {
            if (strokeActive) return
            val now = SystemClock.uptimeMillis()
            if (now - lastPenAt < PEN_NEAR_MS) {
                // The pen is still over the page: more writing is likely on its way, and a
                // release now would be taken back mid-refresh by the next stroke. Wait for the
                // pen to leave, or for the hover to outlast a pause between letters.
                waitingSince = now
                main.removeCallbacks(releaseWhenClear)
                main.postDelayed(releaseWhenClear, RELEASE_TICK_MS)
                return
            }
            release("pen up")
        }

        // The eraser end is not handled in this version.
        override fun onBeginRawErasing(b: Boolean, tp: TouchPoint) {}
        override fun onEndRawErasing(b: Boolean, tp: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(tp: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(tpl: TouchPointList) {}
    }

    private fun PxRect.toRect() = Rect(x, y, right, bottom)

    companion object {
        private const val TAG = "InkBridge"
        /** How long the panel is left alone after a release to finish showing the app's strokes. */
        private const val RELEASE_HOLD_MS = 350L
        private const val REPAINT_PAD_PX = 8
        /** A pen heard from this recently is still over the page. */
        private const val PEN_NEAR_MS = 150L
        /** How long a hovering pen can put a release off, on top of the pen-up delay. */
        private const val HOVER_HOLD_MAX_MS = 1200L
        private const val RELEASE_TICK_MS = 60L
        private const val REPAINT_SOON_MS = 60L
        private const val HOVER_IDLE_MS = 1500L
        private const val WATCHDOG_TICK_MS = 500L
    }
}
