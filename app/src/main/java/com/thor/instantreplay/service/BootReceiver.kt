package com.thor.instantreplay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.thor.instantreplay.TransparentPermissionActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.MY_PACKAGE_REPLACED") {
            Log.i("BootReceiver", "Device booted! Starting Thor Instant Replay...")
            
            val prefs = context.getSharedPreferences("thor_replay_prefs", Context.MODE_PRIVATE)
            val autoBoot = prefs.getBoolean("auto_boot", true)

            if (autoBoot) {
                // Launch transparent activity to obtain MediaProjection token silently (via PROJECT_MEDIA allow)
                val launchIntent = Intent(context, TransparentPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
