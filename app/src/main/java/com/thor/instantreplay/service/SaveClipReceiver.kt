package com.thor.instantreplay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SaveClipReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SaveClipReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ReplayService.ACTION_SAVE_CLIP) {
            Log.i(TAG, "External trigger received: com.thor.instantreplay.ACTION_SAVE_CLIP (handled by ReplayService actionReceiver)")
        }
    }
}
