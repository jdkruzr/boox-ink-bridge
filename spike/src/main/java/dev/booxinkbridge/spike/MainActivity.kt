package dev.booxinkbridge.spike

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView

/** Asks for the overlay permission if it is missing, starts the service, and gets out of the way. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this)
        text.setPadding(48, 96, 48, 48)
        setContentView(text)
        if (!Settings.canDrawOverlays(this)) {
            text.text = "Overlay permission missing. Grant it, then reopen."
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        startForegroundService(Intent(this, SpikeService::class.java))
        text.text = "Spike service running. Drive it over adb."
    }
}
