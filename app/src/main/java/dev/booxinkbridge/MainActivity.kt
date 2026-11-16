package dev.booxinkbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Permissions, status, and the two settings. */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var grant: Button
    private lateinit var pairing: LinearLayout
    private lateinit var pairingText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tuneFromIntent(intent)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }

        status = TextView(this).apply { textSize = 18f }
        root.addView(status)

        grant = Button(this).apply {
            text = "Allow drawing over other apps"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        root.addView(grant)

        pairingText = TextView(this).apply { textSize = 16f }
        pairing = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(pairingText)
            addView(Button(this@MainActivity).apply { text = "Allow"; setOnClickListener { answerPairing(true) } })
            addView(Button(this@MainActivity).apply { text = "Deny"; setOnClickListener { answerPairing(false) } })
        }
        root.addView(pairing)

        root.addView(TextView(this).apply {
            text = "\nPen-up delay, in milliseconds. How long after the pen lifts before the app underneath repaints."
            textSize = 16f
        })
        val prefs = BridgeService.prefs(this)
        val delay = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt(BridgeService.KEY_PEN_UP_DELAY_MS, BridgeService.DEFAULT_PEN_UP_DELAY_MS).toString())
        }
        root.addView(delay)
        root.addView(Button(this).apply {
            text = "Save delay"
            setOnClickListener {
                val ms = delay.text.toString().toIntOrNull()?.coerceIn(100, 5000) ?: BridgeService.DEFAULT_PEN_UP_DELAY_MS
                delay.setText(ms.toString())
                prefs.edit().putInt(BridgeService.KEY_PEN_UP_DELAY_MS, ms).apply()
                startBridge()
            }
        })

        root.addView(TextView(this).apply {
            text = "\nPreview thickness, in percent. Raise it if the line gets heavier when the pen lifts, lower it if it gets lighter."
            textSize = 16f
        })
        val trim = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt(BridgeService.KEY_PREVIEW_TRIM, BridgeService.DEFAULT_PREVIEW_TRIM).toString())
        }
        root.addView(trim)
        root.addView(Button(this).apply {
            text = "Save thickness"
            setOnClickListener {
                val percent = trim.text.toString().toIntOrNull()
                    ?.coerceIn(BridgeService.MIN_PREVIEW_TRIM, BridgeService.MAX_PREVIEW_TRIM) ?: BridgeService.DEFAULT_PREVIEW_TRIM
                trim.setText(percent.toString())
                prefs.edit().putInt(BridgeService.KEY_PREVIEW_TRIM, percent).apply()
                startBridge()
            }
        })

        root.addView(Button(this).apply {
            text = "Forget every app allowed to connect"
            setOnClickListener { prefs.edit().remove(BridgeService.KEY_PAIRED).apply() }
        })

        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tuneFromIntent(intent)
        recreate()
    }

    /**
     * Debug builds only: `am start -n dev.booxinkbridge/.MainActivity --ei trim 70 --ei style 1 --ei repaint 2 --ei always 0`
     * sets the preview trim and the firmware line style, so both can be tried from a computer
     * while someone writes.
     */
    private fun tuneFromIntent(intent: Intent?) {
        if (intent == null || applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val edit = BridgeService.prefs(this).edit()
        if (intent.hasExtra("trim")) {
            edit.putInt(
                BridgeService.KEY_PREVIEW_TRIM,
                intent.getIntExtra("trim", BridgeService.DEFAULT_PREVIEW_TRIM)
                    .coerceIn(BridgeService.MIN_PREVIEW_TRIM, BridgeService.MAX_PREVIEW_TRIM),
            )
        }
        if (intent.hasExtra("repaint")) edit.putInt(BridgeService.KEY_REPAINT_MODE, intent.getIntExtra("repaint", 4).coerceIn(0, 6))
        if (intent.hasExtra("always")) edit.putBoolean(BridgeService.KEY_REPAINT_ALWAYS, intent.getIntExtra("always", 0) != 0)
        if (intent.hasExtra("style")) edit.putInt(BridgeService.KEY_STROKE_STYLE, intent.getIntExtra("style", 0).coerceIn(0, 7))
        edit.commit()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) startBridge()
        main.post(tick)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        super.onPause()
    }

    private fun startBridge() {
        if (Settings.canDrawOverlays(this)) startForegroundService(Intent(this, BridgeService::class.java))
    }

    private fun answerPairing(allow: Boolean) {
        val fingerprint = BridgeService.Status.pairingFingerprint ?: return
        startForegroundService(
            Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_PAIR)
                .putExtra(BridgeService.EXTRA_FINGERPRINT, fingerprint).putExtra(BridgeService.EXTRA_ALLOW, allow)
        )
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = BridgeService.Status
            val allowed = Settings.canDrawOverlays(this@MainActivity)
            grant.visibility = if (allowed) View.GONE else View.VISIBLE
            pairing.visibility = if (s.pairingClient != null) View.VISIBLE else View.GONE
            pairingText.text = "\n\"${s.pairingClient}\" wants to receive your pen strokes. Allow only if you just turned this on in that app."
            status.text = buildString {
                if (!allowed) {
                    append("Overlay permission missing.\n")
                } else if (!s.running) {
                    append("Service not running.\n")
                } else if (s.port == 0) {
                    append("Could not open a port (${Protocol.PORTS.joinToString()} are both taken).\n")
                } else {
                    append("Listening on 127.0.0.1:${s.port}\n")
                }
                append(if (s.client != null) "Connected: ${s.client}\n" else "No note app connected\n")
                append("Last stroke: ${s.lastStroke ?: "none yet"}")
            }
            main.postDelayed(this, 1000)
        }
    }
}
