package dev.booxinkbridge

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * The loopback WebSocket server. One client at a time; a new approved connection replaces the old one.
 * Nobody is a client until its origin and its pairing have both passed (Access.kt).
 * Callbacks arrive on the server's own threads: the listener is expected to hop to the main thread.
 */
class BridgeServer(
    port: Int,
    private val appVersion: String,
    private val device: () -> Triple<String, Int, Int>,
    private val listener: Listener,
) : WebSocketServer(InetSocketAddress(LOOPBACK, port)) {

    interface Listener {
        /** A client finished the handshake. */
        fun onClient(client: String, version: String)
        /** Has the person holding the tablet approved the client with this fingerprint? */
        fun isPaired(fingerprint: String): Boolean
        /** A client nobody has approved yet asked to connect. It has been turned away. */
        fun onPairingRequest(client: String, fingerprint: String)
        /** The current client went away, or was replaced. */
        fun onClientGone()
        fun onFrame(frame: Inbound)
    }

    @Volatile private var client: WebSocket? = null

    init {
        isReuseAddr = true
        // The plugin lives in a WebView that can be frozen for long stretches; do not time it out.
        connectionLostTimeout = 0
    }

    val hasClient: Boolean get() = client?.isOpen == true

    override fun onStart() {
        Log.i(TAG, "listening on ${address.hostString}:${address.port}")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val origin = if (handshake.hasFieldValue("Origin")) handshake.getFieldValue("Origin") else null
        if (!Access.originAllowed(origin)) {
            Log.w(TAG, "refused a connection from origin $origin")
            conn.close(1008, "origin not allowed")
            return
        }
        Log.i(TAG, "socket opened from ${conn.remoteSocketAddress}, origin ${origin ?: "(none)"}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val frame = Protocol.parse(message)
        if (frame == null) {
            Log.i(TAG, "ignored frame: ${message.take(80)}")
            return
        }
        val welcomed = conn.getAttachment<Boolean>() == true
        if (frame is Inbound.Hello) {
            val use = Protocol.negotiate(frame.protocol)
            if (use == null) {
                Log.w(TAG, "hello with protocol ${frame.protocol}: nothing in common, closing")
                conn.close(1002, "no shared protocol version")
                return
            }
            // Before anything else changes: a stranger must not be able to push the real client out.
            if (!Access.tokenWellFormed(frame.token)) {
                Log.w(TAG, "hello from ${frame.client} without a usable token: refused")
                conn.send(Protocol.denied("token"))
                conn.close(1008, "token required")
                return
            }
            val fingerprint = Access.fingerprint(frame.token)
            if (!listener.isPaired(fingerprint)) {
                Log.i(TAG, "hello from ${frame.client}, not paired yet: asking")
                listener.onPairingRequest(frame.client.take(40), fingerprint)
                conn.send(Protocol.denied("pairing"))
                conn.close(1008, "pairing required")
                return
            }
            val old = client
            if (old != null && old !== conn) {
                Log.i(TAG, "a new client replaces the old one")
                client = null
                listener.onClientGone()
                old.close(1000, "replaced")
            }
            client = conn
            conn.setAttachment(true)
            val (model, w, h) = device()
            conn.send(Protocol.welcome(use, appVersion, model, w, h))
            Log.i(TAG, "hello from ${frame.client} ${frame.version}, protocol $use")
            listener.onClient(frame.client, frame.version)
            return
        }
        if (!welcomed || conn !== client) {
            Log.i(TAG, "frame before hello, or from a replaced client: dropped")
            return
        }
        listener.onFrame(frame)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.i(TAG, "socket closed code=$code remote=$remote")
        if (conn === client) {
            client = null
            listener.onClientGone()
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w(TAG, "socket error: ${ex.message}")
    }

    /** False when there is no client to send to. Safe from any thread. */
    fun sendToClient(text: String): Boolean {
        val c = client ?: return false
        return try {
            if (!c.isOpen) return false
            c.send(text)
            true
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "InkBridge"

        /**
         * 127.0.0.1 spelled out. `InetAddress.getLoopbackAddress()` answers ::1 on this
         * tablet, and the protocol's URL is the IPv4 one: a WebView asking for 127.0.0.1
         * would find nobody home.
         */
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** The first of the protocol's ports that can be bound on loopback, or null. */
        fun freePort(): Int? {
            for (p in Protocol.PORTS) {
                try {
                    ServerSocket().use {
                        it.reuseAddress = true
                        it.bind(InetSocketAddress(LOOPBACK, p))
                    }
                    return p
                } catch (e: Exception) {
                    Log.w(TAG, "port $p is taken: ${e.message}")
                }
            }
            return null
        }
    }
}
