package com.thor.instantreplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import com.thor.instantreplay.service.ReplayService

class TransparentPermissionActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_CAPTURE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val prefs = getSharedPreferences("thor_replay_prefs", Context.MODE_PRIVATE)
                val durationSec = prefs.getLong("duration_sec", 60L)
                val dualScreen = prefs.getBoolean("dual_screen", true)
                val recordMic = prefs.getBoolean("record_mic", true)
                val noiseFilter = prefs.getBoolean("noise_filter", true)

                val serviceIntent = Intent(this, ReplayService::class.java).apply {
                    action = ReplayService.ACTION_START
                    putExtra(ReplayService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ReplayService.EXTRA_RESULT_DATA, data)
                    putExtra(ReplayService.EXTRA_DURATION_SECONDS, durationSec)
                    putExtra(ReplayService.EXTRA_DUAL_SCREEN, dualScreen)
                    putExtra(ReplayService.EXTRA_RECORD_MIC, recordMic)
                    putExtra(ReplayService.EXTRA_NOISE_FILTER, noiseFilter)
                }
                startForegroundService(serviceIntent)
                Log.i("TransparentPerm", "MediaProjection acquired silently and service started")
            } else {
                Log.w("TransparentPerm", "MediaProjection permission denied or cancelled: $resultCode")
            }
            finish()
        }
    }
}
