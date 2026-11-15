package dev.booxinkbridge

import android.app.Application
import android.util.Log

class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The Onyx SDK reaches hidden framework APIs; without this it fails on Android 14+.
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (t: Throwable) {
            Log.w("InkBridge", "HiddenApiBypass failed: ${t.message}")
        }
    }
}
