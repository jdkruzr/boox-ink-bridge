package dev.booxinkbridge

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/** One frame from the plugin. See docs/PROTOCOL.md. */
sealed class Inbound {
    data class Hello(val protocol: Int, val client: String, val version: String, val token: String) : Inbound()
    data class Surface(
        val rev: Int,
        val kind: String,
        val rect: CssRect,
        val exclude: List<CssRect>,
        val viewportW: Double,
        val viewportH: Double,
        val dpr: Double,
    ) : Inbound()
    data class Pen(val color: String, val width: Double) : Inbound()
    data class Pause(val reason: String) : Inbound()
    object Resume : Inbound()
    object Close : Inbound()
    data class Landed(val ids: List<Int>) : Inbound()
}

/** One stroke point on the wire: viewport CSS px, pressure 0..1, ms since the stroke's first point. */
data class WirePoint(val x: Double, val y: Double, val pressure: Double, val t: Long)

object Protocol {
    const val VERSION = 1
    const val APP = "boox-ink-bridge"
    val PORTS = intArrayOf(47810, 47811)

    /** The version to speak with a client offering `clientProtocol`, or null when there is none in common. */
    fun negotiate(clientProtocol: Int): Int? = if (clientProtocol >= 1) minOf(clientProtocol, VERSION) else null

    /** Null for anything that is not a known, well-formed frame: unknown types are ignored, not errors. */
    fun parse(text: String): Inbound? {
        val o = try { JSONObject(text) } catch (e: Exception) { return null }
        return try {
            when (o.optString("type")) {
                "hello" -> Inbound.Hello(o.getInt("protocol"), o.optString("client"), o.optString("version"), o.optString("token"))
                "surface" -> {
                    val vp = o.getJSONObject("viewport")
                    val ex = o.optJSONArray("exclude")
                    val exclude = ArrayList<CssRect>()
                    if (ex != null) for (i in 0 until ex.length()) exclude.add(rect(ex.getJSONObject(i)))
                    Inbound.Surface(
                        o.getInt("rev"), o.optString("kind", "note"), rect(o.getJSONObject("rect")), exclude,
                        vp.getDouble("w"), vp.getDouble("h"), o.optDouble("dpr", 1.0),
                    )
                }
                "pen" -> Inbound.Pen(o.getString("color"), o.getDouble("width"))
                "pause" -> Inbound.Pause(o.optString("reason"))
                "resume" -> Inbound.Resume
                "close" -> Inbound.Close
                "landed" -> {
                    val a = o.getJSONArray("ids")
                    Inbound.Landed((0 until a.length()).map { a.getInt(it) })
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun rect(o: JSONObject) = CssRect(o.getDouble("x"), o.getDouble("y"), o.getDouble("w"), o.getDouble("h"))

    fun welcome(protocol: Int, version: String, model: String, widthPx: Int, heightPx: Int): String =
        JSONObject()
            .put("type", "welcome")
            .put("protocol", protocol)
            .put("app", APP)
            .put("version", version)
            .put("device", JSONObject().put("model", model).put("widthPx", widthPx).put("heightPx", heightPx))
            .toString()

    /** Sent in place of `welcome`, just before the socket is closed. */
    fun denied(reason: String): String = JSONObject().put("type", "denied").put("reason", reason).toString()

    fun strokeBegin(id: Int, rev: Int): String =
        JSONObject().put("type", "stroke-begin").put("id", id).put("rev", rev).toString()

    fun stroke(id: Int, rev: Int, points: List<WirePoint>): String {
        val arr = JSONArray()
        for (p in points) {
            arr.put(JSONArray().put(round(p.x, 100.0)).put(round(p.y, 100.0)).put(round(p.pressure, 1000.0)).put(p.t))
        }
        return JSONObject().put("type", "stroke").put("id", id).put("rev", rev).put("points", arr).toString()
    }

    fun strokeCancel(id: Int): String = JSONObject().put("type", "stroke-cancel").put("id", id).toString()

    /** Hundredths of a CSS pixel and thousandths of pressure are finer than the pen; the rest is noise on the wire. */
    private fun round(v: Double, scale: Double): Double = (v * scale).roundToLong() / scale

    /** `#rrggbb` to opaque ARGB, or null when it is not that. */
    fun parseColor(s: String): Int? {
        if (s.length != 7 || s[0] != '#') return null
        val rgb = s.substring(1).toIntOrNull(16) ?: return null
        return (0xFF shl 24) or rgb
    }
}
