package dev.booxinkbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTest {
    @Test fun aNativeClientWithNoOriginPassesTheOriginCheck() {
        assertTrue(Access.originAllowed(null))
        assertTrue(Access.originAllowed(""))
    }

    @Test fun aNoteAppsWebViewPasses() {
        assertTrue(Access.originAllowed("http://localhost"))
        assertTrue(Access.originAllowed("HTTP://LOCALHOST/"))
        assertTrue(Access.originAllowed("app://obsidian.md"))
    }

    @Test fun aWebPageIsRefused() {
        assertFalse(Access.originAllowed("https://example.com"))
        assertFalse(Access.originAllowed("http://localhost:8080"))
        assertFalse(Access.originAllowed("http://localhost.example.com"))
        assertFalse(Access.originAllowed("http://127.0.0.1"))
        assertFalse(Access.originAllowed("null"))
        assertFalse(Access.originAllowed("file://"))
    }

    @Test fun aTokenMustBeLongAndPlain() {
        assertTrue(Access.tokenWellFormed("k3Jw9QmZ7xT2vB8nR4pL6sD1fH5gY0aC"))
        assertFalse(Access.tokenWellFormed(""))
        assertFalse(Access.tokenWellFormed("short"))
        assertFalse(Access.tokenWellFormed("k3Jw9QmZ7xT2vB8nR4pL6sD1fH5gY0a C"))
        assertFalse(Access.tokenWellFormed("x".repeat(257)))
    }

    @Test fun theFingerprintIsStableAndIsNotTheToken() {
        val token = "k3Jw9QmZ7xT2vB8nR4pL6sD1fH5gY0aC"
        assertEquals(Access.fingerprint(token), Access.fingerprint(token))
        assertEquals(64, Access.fingerprint(token).length)
        assertNotEquals(token, Access.fingerprint(token))
        assertNotEquals(Access.fingerprint(token), Access.fingerprint(token + "x"))
    }
}
