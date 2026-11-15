package dev.booxinkbridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Both ends of the bridge are tested against the same files in the repo's `fixtures/`. */
class ProtocolTest {

    private fun fixture(name: String): JSONArray {
        val f = sequenceOf(File("../fixtures/$name"), File("fixtures/$name")).first { it.exists() }
        return JSONArray(f.readText())
    }

    @Test
    fun everyPluginFrameParses() {
        val frames = fixture("plugin-to-companion.json")
        assertTrue(frames.length() > 0)
        for (i in 0 until frames.length()) {
            val o = frames.getJSONObject(i)
            assertNotNull("frame ${o.getString("type")} did not parse", Protocol.parse(o.toString()))
        }
    }

    @Test
    fun pluginFramesCarryTheirFields() {
        val frames = fixture("plugin-to-companion.json")
        val parsed = (0 until frames.length()).map { Protocol.parse(frames.getJSONObject(it).toString())!! }
        assertEquals(Inbound.Hello(1, "handwriting", "1.5.1", "k3Jw9QmZ7xT2vB8nR4pL6sD1fH5gY0aC"), parsed[0])
        val s = parsed[1] as Inbound.Surface
        assertEquals(1, s.rev)
        assertEquals("note", s.kind)
        assertEquals(CssRect(0.0, 112.5, 768.0, 560.0), s.rect)
        assertEquals(listOf(CssRect(182.0, 159.0, 560.0, 44.0)), s.exclude)
        assertEquals(768.0, s.viewportW, 0.0)
        assertEquals(1024.0, s.viewportH, 0.0)
        assertEquals(2.0, s.dpr, 0.0)
        assertEquals(Inbound.Pen("#1f5fd6", 2.4), parsed[2])
        assertEquals(Inbound.Pause("tool"), parsed[3])
        assertEquals(Inbound.Resume, parsed[4])
        assertEquals(Inbound.Landed(listOf(1, 2)), parsed[5])
        assertEquals(Inbound.Close, parsed[6])
    }

    @Test
    fun companionFramesRoundTrip() {
        val frames = fixture("companion-to-plugin.json")
        for (i in 0 until frames.length()) {
            val want = frames.getJSONObject(i)
            val got = when (want.getString("type")) {
                "welcome" -> {
                    val d = want.getJSONObject("device")
                    Protocol.welcome(want.getInt("protocol"), want.getString("version"), d.getString("model"), d.getInt("widthPx"), d.getInt("heightPx"))
                }
                "stroke-begin" -> Protocol.strokeBegin(want.getInt("id"), want.getInt("rev"))
                "stroke" -> {
                    val pts = want.getJSONArray("points")
                    Protocol.stroke(
                        want.getInt("id"), want.getInt("rev"),
                        (0 until pts.length()).map {
                            val p = pts.getJSONArray(it)
                            WirePoint(p.getDouble(0), p.getDouble(1), p.getDouble(2), p.getLong(3))
                        },
                    )
                }
                "stroke-cancel" -> Protocol.strokeCancel(want.getInt("id"))
                "denied" -> Protocol.denied(want.getString("reason"))
                else -> error("fixture has a type the encoder does not know: ${want.getString("type")}")
            }
            assertTrue("encoded ${want.getString("type")} differs: $got", want.similar(JSONObject(got)))
        }
    }

    @Test
    fun unknownAndMalformedFramesAreIgnored() {
        assertNull(Protocol.parse("""{"type":"from-the-future","x":1}"""))
        assertNull(Protocol.parse("not json"))
        assertNull(Protocol.parse("""{"type":"surface","rev":1}"""))
        assertNotNull(Protocol.parse("""{"type":"resume","extra":true}"""))
    }

    @Test
    fun versionNegotiation() {
        assertEquals(1, Protocol.negotiate(1))
        assertEquals(1, Protocol.negotiate(7))
        assertNull(Protocol.negotiate(0))
    }

    @Test
    fun colours() {
        assertEquals(0xFF1F5FD6.toInt(), Protocol.parseColor("#1f5fd6"))
        assertNull(Protocol.parseColor("blue"))
        assertNull(Protocol.parseColor("#12345"))
    }

    @Test
    fun strokePointsAreRounded() {
        val o = JSONObject(Protocol.stroke(3, 2, listOf(WirePoint(10.123456, 20.987654, 0.123456, 7))))
        val p = o.getJSONArray("points").getJSONArray(0)
        assertEquals(10.12, p.getDouble(0), 1e-9)
        assertEquals(20.99, p.getDouble(1), 1e-9)
        assertEquals(0.123, p.getDouble(2), 1e-9)
        assertEquals(7L, p.getLong(3))
    }
}
