package dev.booxinkbridge

import java.security.MessageDigest

/**
 * Who may connect.
 *
 * The server listens on loopback, which keeps the network out but not the tablet itself:
 * any app installed here, and any web page open in a browser here, can open a socket to
 * 127.0.0.1. Whoever connects gets every pen stroke and decides where the overlay goes.
 * So two checks, both before a client is treated as one:
 *
 *  - ORIGIN. A browser states the page's origin on every WebSocket handshake and a page
 *    cannot forge it. Anything that names a web origin is refused, except the handful a
 *    note app's own WebView uses.
 *  - PAIRING. An app is not a browser and can say what it likes, so each client also
 *    presents a secret of its own making, and the person holding the tablet approves it
 *    once. Only its hash is kept.
 *
 * Pure: no Android imports, so the rules run under plain JVM tests.
 */
object Access {
    /** Origins a note app's WebView presents. Obsidian on Android is `http://localhost`. */
    private val APP_ORIGINS = setOf("http://localhost", "https://localhost", "capacitor://localhost", "app://obsidian.md")

    /** A secret shorter than this is not one. */
    const val MIN_TOKEN_LENGTH = 32

    /** `origin` is the handshake's Origin header, or null when the client sent none (a native client). */
    fun originAllowed(origin: String?): Boolean {
        if (origin.isNullOrBlank() || origin == "null") return origin.isNullOrBlank()
        return origin.trim().trimEnd('/').lowercase() in APP_ORIGINS
    }

    fun tokenWellFormed(token: String): Boolean =
        token.length in MIN_TOKEN_LENGTH..256 && token.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** What is stored and compared: never the secret itself. */
    fun fingerprint(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
