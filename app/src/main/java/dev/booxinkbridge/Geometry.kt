package dev.booxinkbridge

import kotlin.math.roundToInt

/** A rectangle in CSS pixels of the WebView's viewport. */
data class CssRect(val x: Double, val y: Double, val w: Double, val h: Double)

/** A rectangle in whole screen pixels. */
data class PxRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
}

/**
 * The one conversion between the plugin's CSS pixels and the screen's pixels.
 *
 * `scale = screen width in px / viewport width in CSS px`. The origin is where the
 * WebView's top-left sits on the screen, 0,0 unless calibrated otherwise.
 */
class Geometry(screenWidthPx: Int, viewportWidthCss: Double, val originX: Int = 0, val originY: Int = 0) {

    val scale: Double = if (viewportWidthCss > 0) screenWidthPx / viewportWidthCss else 1.0

    fun toScreen(r: CssRect): PxRect {
        val left = (originX + r.x * scale).roundToInt()
        val top = (originY + r.y * scale).roundToInt()
        val right = (originX + (r.x + r.w) * scale).roundToInt()
        val bottom = (originY + (r.y + r.h) * scale).roundToInt()
        return PxRect(left, top, maxOf(0, right - left), maxOf(0, bottom - top))
    }

    /** A screen rectangle relative to `outer`'s top-left, clipped to `outer`. Null when nothing is left. */
    fun localTo(outer: PxRect, inner: PxRect): PxRect? {
        val left = maxOf(inner.x, outer.x)
        val top = maxOf(inner.y, outer.y)
        val right = minOf(inner.right, outer.right)
        val bottom = minOf(inner.bottom, outer.bottom)
        if (right <= left || bottom <= top) return null
        return PxRect(left - outer.x, top - outer.y, right - left, bottom - top)
    }

    fun cssX(screenXPx: Double): Double = (screenXPx - originX) / scale
    fun cssY(screenYPx: Double): Double = (screenYPx - originY) / scale
}

/**
 * The firmware line's width in screen px: the width the plugin asked for, scaled to the
 * screen, times the user's trim. Never under one pixel, which the firmware draws as nothing.
 */
fun previewWidthPx(widthCss: Double, scale: Double, trimPercent: Int): Float =
    (widthCss * scale * trimPercent / 100.0).toFloat().coerceAtLeast(1f)
