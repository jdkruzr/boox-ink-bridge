package dev.booxinkbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeometryTest {

    // The Note Mini C: 1536 px wide, a 768 CSS px viewport.
    private val g = Geometry(1536, 768.0)

    @Test
    fun scaleComesFromTheViewportWidth() {
        assertEquals(2.0, g.scale, 0.0)
        assertEquals(1.0, Geometry(1536, 0.0).scale, 0.0)
    }

    @Test
    fun cssRectToScreen() {
        assertEquals(PxRect(0, 225, 1536, 1120), g.toScreen(CssRect(0.0, 112.5, 768.0, 560.0)))
    }

    @Test
    fun originShiftsBothWays() {
        val o = Geometry(1536, 768.0, originX = 10, originY = 48)
        assertEquals(PxRect(30, 68, 200, 100), o.toScreen(CssRect(10.0, 10.0, 100.0, 50.0)))
        assertEquals(10.0, o.cssX(30.0), 1e-9)
        assertEquals(10.0, o.cssY(68.0), 1e-9)
    }

    @Test
    fun screenBackToCss() {
        // A point 627 px into an overlay that sits at screen y 450.
        assertEquals(313.5, g.cssX(627.0), 1e-9)
        assertEquals(490.5, g.cssY(531.0 + 450.0), 1e-9)
    }

    @Test
    fun excludeBecomesOverlayLocalAndClipped() {
        val overlay = g.toScreen(CssRect(0.0, 112.5, 768.0, 560.0))
        val bar = g.toScreen(CssRect(182.0, 159.0, 560.0, 44.0))
        assertEquals(PxRect(364, 93, 1120, 88), g.localTo(overlay, bar))
        // Hanging off the top edge: only the part inside survives.
        assertEquals(PxRect(0, 0, 200, 25), g.localTo(overlay, PxRect(0, 200, 200, 50)))
        assertNull(g.localTo(overlay, PxRect(0, 0, 100, 100)))
    }
}
