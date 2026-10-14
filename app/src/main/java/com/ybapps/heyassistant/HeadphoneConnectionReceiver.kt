package com.ybapps.heyassistant

import android.Manifest
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class HeadphoneConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            Intent.ACTION_HEADSET_PLUG -> intent.getIntExtra("state", 0) == 1
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED" ->
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED) == BluetoothProfile.STATE_CONNECTED
            else -> false
        }
        if (!connected) return

        val prefs = context.getSharedPreferences(VoiceAssistantService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(VoiceAssistantService.KEY_AUTO_START, false)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("service_active", false)) return

        try {
            val serviceIntent = Intent(context, VoiceAssistantService::class.java).apply {
                action = VoiceAssistantService.ACTION_START
                putExtra(VoiceAssistantService.EXTRA_LANGUAGE, "en-US")
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.w("HeyAssistant", "Could not auto-start after headset connection", e)
        }
    }
}
