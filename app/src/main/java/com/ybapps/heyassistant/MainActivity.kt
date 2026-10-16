package com.ybapps.heyassistant

import android.Manifest
import android.bluetooth.BluetoothProfile
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var pendingActivation = false
    private var autoStartEnabled by mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        refreshHeadphoneStatus()
        if (pendingActivation && results.values.all { it }) {
            val prefs = getSharedPreferences(VoiceAssistantService.PREFS, MODE_PRIVATE)
            prefs.edit().putBoolean(VoiceAssistantService.KEY_AUTO_START, true).apply()
            autoStartEnabled = true
            val startIntent = Intent(this, VoiceAssistantService::class.java).apply {
                action = VoiceAssistantService.ACTION_START
                putExtra(VoiceAssistantService.EXTRA_LANGUAGE, "en-US")
            }
            try {
                ContextCompat.startForegroundService(this, startIntent)
                serviceActive = true
            } catch (_: Exception) {
                serviceActive = false
            }
        }
        pendingActivation = false
    }

    private var headphonesConnected by mutableStateOf(false)
    private var serviceActive by mutableStateOf(false)
    private var receiverRegistered = false

    private val headphoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_HEADSET_PLUG -> {
                    headphonesConnected = intent.getIntExtra("state", 0) == 1
                    saveHeadphoneState(headphonesConnected)
                }
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
                "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED" -> {
                    if (Build.VERSION.SDK_INT < 31 ||
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                        if (state == BluetoothProfile.STATE_CONNECTED) headphonesConnected = true
                        else if (state == BluetoothProfile.STATE_DISCONNECTED) refreshHeadphoneStatus()
                        saveHeadphoneState(headphonesConnected)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED")
        }
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(headphoneReceiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(headphoneReceiver, filter)
            receiverRegistered = true
        }
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        if (sticky != null) headphonesConnected = sticky.getIntExtra("state", 0) == 1
        serviceActive = getSharedPreferences(VoiceAssistantService.PREFS, MODE_PRIVATE).getBoolean("service_active", false)
        saveHeadphoneState(headphonesConnected)
        refreshHeadphoneStatus()
    }

    override fun onPause() {
        if (receiverRegistered) {
            try { unregisterReceiver(headphoneReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun saveHeadphoneState(connected: Boolean) {
        getSharedPreferences(VoiceAssistantService.PREFS, MODE_PRIVATE)
            .edit().putBoolean("headphones_connected", connected).apply()
    }

    private fun refreshHeadphoneStatus() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val connected = outputDevices.any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> true
                else -> false
            }
        }
        headphonesConnected = connected
        saveHeadphoneState(connected)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(VoiceAssistantService.PREFS, MODE_PRIVATE)
        headphonesConnected = prefs.getBoolean("headphones_connected", false)
        serviceActive = prefs.getBoolean("service_active", false)
        autoStartEnabled = prefs.getBoolean(VoiceAssistantService.KEY_AUTO_START, false)
        setContent {
            val enabled = autoStartEnabled
            val connected = headphonesConnected
            val active = serviceActive
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF54E0C1),
                background = Color(0xFF0A0E13),
                surface = Color(0xFF151D26)
            )) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0E13)) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("היי עוזר", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF54E0C1))
                        Text("פקודות מוזיקה קוליות • yb apps", color = Color.LightGray, fontSize = 14.sp)
                        Spacer(Modifier.height(22.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF151D26), RoundedCornerShape(24.dp)).padding(18.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(if (connected) "● אוזניות מחוברות" else "● אוזניות לא מחוברות", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (connected) Color(0xFF54E0C1) else Color(0xFFFF7B86))
                            Spacer(Modifier.height(8.dp))
                            Text(if (active) "סטטוס העוזר: פעיל ומאזין" else "סטטוס העוזר: לא פעיל", fontSize = 15.sp, color = if (active) Color(0xFF54E0C1) else Color(0xFFB9C7D6))
                            Spacer(Modifier.height(12.dp))
                            Text(if (enabled) "ההפעלה האוטומטית מופעלת" else "העוזר מוכן", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("כשההאזנה פעילה, אין צורך לומר מילת הפעלה לפני כל פקודה.", color = Color(0xFFB9C7D6), fontSize = 13.sp)
                            Spacer(Modifier.height(14.dp))
                            Text("הפקודות הקבועות", fontWeight = FontWeight.Bold, color = Color(0xFF54E0C1))
                            Spacer(Modifier.height(8.dp))
                            Text("נגן מוזיקה  ·  Play Music")
                            Text("עצור מוזיקה  ·  Stop Music")
                            Text("השיר הבא  ·  Next Song")
                            Text("השיר הקודם  ·  Previous Song")
                            Text("הגבר קול  ·  Volume Up")
                            Text("הנמך קול  ·  Volume Down")
                            Text("עצור את העוזר  ·  Stop Assistant")
                            Spacer(Modifier.height(14.dp))
                            Text("חבר אוזניות והפעל את ההאזנה. אפשר לומר את הפקודות באנגלית; לזיהוי בעברית נדרשת תמיכה של שירות הזיהוי במכשיר.", color = Color(0xFF8D9BAA), fontSize = 13.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                                val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                                if (Build.VERSION.SDK_INT >= 31) permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                                val missing = permissions.filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                                if (missing.isNotEmpty()) {
                                    pendingActivation = true
                                    permissionLauncher.launch(missing.toTypedArray())
                                } else {
                                    val currentPrefs = getSharedPreferences(VoiceAssistantService.PREFS, MODE_PRIVATE)
                                    if (enabled) {
                                        currentPrefs.edit().putBoolean(VoiceAssistantService.KEY_AUTO_START, false).apply()
                                        if (currentPrefs.getBoolean("service_active", false)) {
                                            val stopIntent = Intent(this@MainActivity, VoiceAssistantService::class.java).apply { action = VoiceAssistantService.ACTION_STOP }
                                            startService(stopIntent)
                                        }
                                        autoStartEnabled = false
                                        serviceActive = false
                                    } else {
                                        currentPrefs.edit().putBoolean(VoiceAssistantService.KEY_AUTO_START, true).apply()
                                        val startIntent = Intent(this@MainActivity, VoiceAssistantService::class.java).apply {
                                            action = VoiceAssistantService.ACTION_START
                                            putExtra(VoiceAssistantService.EXTRA_LANGUAGE, "en-US")
                                        }
                                        ContextCompat.startForegroundService(this@MainActivity, startIntent)
                                        autoStartEnabled = true
                                        serviceActive = true
                                    }
                                }
                            }) { Text(if (enabled) "כבה את העוזר" else "הפעל האזנה רציפה") }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("מצב האוזניות נבדק בפתיחת האפליקציה ומתעדכן באירועי חיבור וניתוק.", color = Color(0xFF718096), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
