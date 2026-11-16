package dev.booxinkbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/** Owns the WebSocket server and the overlay, for as long as the notification is up. */
class BridgeService : Service(), BridgeServer.Listener, OverlayController.Sink {

    private val main = Handler(Looper.getMainLooper())
    private var server: BridgeServer? = null
    private lateinit var overlay: OverlayController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ink Bridge", NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("Ink Bridge")
            .setContentText("Waiting for a note app")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, note)
        }
        overlay = OverlayController(this, this)
        readSettings()
        startServer()
        Status.running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAIR) answerPairing(intent.getStringExtra(EXTRA_FINGERPRINT), intent.getBooleanExtra(EXTRA_ALLOW, false))
        // MainActivity starts the service again after a setting changes.
        readSettings()
        return START_STICKY
    }

    private fun readSettings() {
        val p = prefs(this)
        overlay.penUpDelayMs = p.getInt(KEY_PEN_UP_DELAY_MS, DEFAULT_PEN_UP_DELAY_MS)
        overlay.previewTrimPercent = p.getInt(KEY_PREVIEW_TRIM, DEFAULT_PREVIEW_TRIM).coerceIn(MIN_PREVIEW_TRIM, MAX_PREVIEW_TRIM)
        overlay.strokeStyle = p.getInt(KEY_STROKE_STYLE, 0)
        overlay.repaintMode = p.getInt(KEY_REPAINT_MODE, 1)
        overlay.originX = p.getInt(KEY_ORIGIN_X, 0)
        overlay.originY = p.getInt(KEY_ORIGIN_Y, 0)
    }

    private fun startServer() {
        val port = BridgeServer.freePort()
        if (port == null) {
            Log.e(TAG, "no free port among ${Protocol.PORTS.joinToString()}")
            Status.port = 0
            return
        }
        val s = BridgeServer(port, BuildConfig.VERSION_NAME, {
            val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            Triple(Build.MODEL, b.width(), b.height())
        }, this)
        s.start()
        server = s
        Status.port = port
    }

    // ---- BridgeServer.Listener: server threads, so everything hops to main ------

    override fun isPaired(fingerprint: String): Boolean =
        prefs(this).getStringSet(KEY_PAIRED, emptySet())?.contains(fingerprint) == true

    override fun onPairingRequest(client: String, fingerprint: String) {
        main.post {
            Status.pairingClient = client
            Status.pairingFingerprint = fingerprint
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PAIRING, "Connection requests", NotificationManager.IMPORTANCE_HIGH))
            fun answer(allow: Boolean, request: Int): PendingIntent = PendingIntent.getService(
                this, request,
                Intent(this, BridgeService::class.java).setAction(ACTION_PAIR)
                    .putExtra(EXTRA_FINGERPRINT, fingerprint).putExtra(EXTRA_ALLOW, allow),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                PAIRING_NOTIFICATION,
                Notification.Builder(this, CHANNEL_PAIRING)
                    .setContentTitle("Let \"$client\" use the pen?")
                    .setContentText("An app on this tablet wants to receive your pen strokes. Allow only if you just turned this on.")
                    .setSmallIcon(android.R.drawable.ic_menu_edit)
                    .setContentIntent(open)
                    .addAction(Notification.Action.Builder(null, "Allow", answer(true, 1)).build())
                    .addAction(Notification.Action.Builder(null, "Deny", answer(false, 2)).build())
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    /** The person's answer to a pairing request, from the notification or the activity. */
    private fun answerPairing(fingerprint: String?, allow: Boolean) {
        // Only the request that is actually pending can be answered.
        if (fingerprint == null || fingerprint != Status.pairingFingerprint) return
        if (allow) {
            val p = prefs(this)
            val paired = HashSet(p.getStringSet(KEY_PAIRED, emptySet()) ?: emptySet())
            paired.add(fingerprint)
            p.edit().putStringSet(KEY_PAIRED, paired).apply()
            Log.i(TAG, "paired with ${Status.pairingClient}")
        } else {
            Log.i(TAG, "pairing with ${Status.pairingClient} denied")
        }
        Status.pairingClient = null
        Status.pairingFingerprint = null
        getSystemService(NotificationManager::class.java).cancel(PAIRING_NOTIFICATION)
    }

    override fun onClient(client: String, version: String) {
        main.post {
            Status.client = "$client $version"
        }
    }

    override fun onClientGone() {
        main.post {
            Log.i(TAG, "client gone: overlay closed")
            Status.client = null
            overlay.close()
        }
    }

    override fun onFrame(frame: Inbound) {
        main.post {
            when (frame) {
                is Inbound.Surface -> {
                    Log.i(TAG, "surface rev=${frame.rev} kind=${frame.kind} rect=${frame.rect} exclude=${frame.exclude.size}")
                    overlay.surface(frame)
                }
                is Inbound.Pen -> {
                    Log.i(TAG, "pen ${frame.color} width=${frame.width}")
                    overlay.pen(frame)
                }
                is Inbound.Pause -> {
                    Log.i(TAG, "pause (${frame.reason})")
                    overlay.pause()
                }
                Inbound.Resume -> {
                    Log.i(TAG, "resume")
                    overlay.resume()
                }
                Inbound.Close -> {
                    Log.i(TAG, "close")
                    overlay.close()
                }
                is Inbound.Landed -> Log.i(TAG, "landed ${frame.ids}")
                is Inbound.Hello -> {}
            }
        }
    }

    // ---- OverlayController.Sink: main thread ------------------------------------

    override fun strokeBegin(id: Int, rev: Int) {
        server?.sendToClient(Protocol.strokeBegin(id, rev))
    }

    override fun stroke(id: Int, rev: Int, points: List<WirePoint>) {
        val sent = server?.sendToClient(Protocol.stroke(id, rev, points)) == true
        Log.i(TAG, "stroke id=$id rev=$rev points=${points.size} sent=$sent")
        Status.lastStroke = "#$id, ${points.size} points" + if (sent) "" else " (not sent)"
    }

    override fun onDestroy() {
        Status.running = false
        Status.client = null
        Status.port = 0
        overlay.close()
        try { server?.stop(500) } catch (e: Exception) { Log.w(TAG, "stop: ${e.message}") }
        server = null
        super.onDestroy()
    }

    /** What MainActivity shows. Written on the main thread. */
    object Status {
        @Volatile var running = false
        @Volatile var port = 0
        @Volatile var client: String? = null
        @Volatile var lastStroke: String? = null
        /** A connection request waiting for an answer: who asked, and the fingerprint of its secret. */
        @Volatile var pairingClient: String? = null
        @Volatile var pairingFingerprint: String? = null
    }

    companion object {
        private const val TAG = "InkBridge"
        private const val CHANNEL = "bridge"
        const val KEY_PEN_UP_DELAY_MS = "penUpDelayMs"
        const val KEY_ORIGIN_X = "originX"
        const val KEY_ORIGIN_Y = "originY"
        const val DEFAULT_PEN_UP_DELAY_MS = 400
        const val KEY_PREVIEW_TRIM = "previewTrimPercent"
        const val DEFAULT_PREVIEW_TRIM = 100
        const val MIN_PREVIEW_TRIM = 25
        const val MAX_PREVIEW_TRIM = 300
        /** Set only from adb on a debug build, for trying the firmware's other line styles. */
        const val KEY_STROKE_STYLE = "strokeStyle"
        const val KEY_REPAINT_MODE = "repaintMode"
        const val KEY_PAIRED = "pairedFingerprints"
        const val ACTION_PAIR = "dev.booxinkbridge.PAIR"
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_ALLOW = "allow"
        private const val CHANNEL_PAIRING = "pairing"
        private const val PAIRING_NOTIFICATION = 2

        fun prefs(context: Context) = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    }
}
