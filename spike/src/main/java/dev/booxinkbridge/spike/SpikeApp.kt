package dev.booxinkbridge.spike

import android.app.Application
import android.util.Log

class SpikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The Onyx SDK reaches hidden framework APIs; without this it fails on Android 14+.
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (t: Throwable) {
            Log.w("InkSpike", "HiddenApiBypass failed: ${t.message}")
        }
    }
}
