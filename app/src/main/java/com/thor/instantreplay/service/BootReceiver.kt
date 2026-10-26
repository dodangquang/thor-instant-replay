package com.thor.instantreplay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.thorqol.shared.LicenseAccess
import com.thor.instantreplay.TransparentPermissionActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.MY_PACKAGE_REPLACED") {
            Log.i("BootReceiver", "Boot/package event received")

            val prefs = context.getSharedPreferences("thor_replay_prefs", Context.MODE_PRIVATE)
            val autoBoot = prefs.getBoolean("auto_boot", true)

            // Respect an explicit stop: auto-starting a replay buffer the user turned
            // off is what made the app look impossible to shut down.
            if (ReplayService.isUserStopped(context)) {
                Log.i("BootReceiver", "Skipping auto-start: replay was stopped explicitly")
                return
            }

            if (autoBoot && LicenseAccess.canUse(context)) {
                // Launch transparent activity to obtain a MediaProjection token silently
                // (requires the PROJECT_MEDIA app-op, see README)
                val launchIntent = Intent(context, TransparentPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
