package com.ybapps.heyassistant

import android.Manifest
import android.content.Intent
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
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    private var refreshStatus by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        refreshStatus++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val refresh = refreshStatus
            var enabled by remember { mutableStateOf(
                getSharedPreferences(VoiceAssistantService.PREFS, MODE_PRIVATE)
                    .getBoolean(VoiceAssistantService.KEY_AUTO_START, false)
            ) }
            val prefs = getSharedPreferences(VoiceAssistantService.PREFS, MODE_PRIVATE)
            val headphonesConnected = prefs.getBoolean("headphones_connected", false)
            val serviceActive = prefs.getBoolean("service_active", false)
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF54E0C1),
                background = Color(0xFF0A0E13),
                surface = Color(0xFF151D26)
            )) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0E13)) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("היי עוזר", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF54E0C1))
                        Text("פקודות מוזיקה קוליות • yb apps", color = Color.LightGray, fontSize = 14.sp)
                        Spacer(Modifier.height(22.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF151D26), RoundedCornerShape(24.dp)).padding(18.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(if (headphonesConnected) "● אוזניות מחוברות" else "● אוזניות לא מחוברות", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (headphonesConnected) Color(0xFF54E0C1) else Color(0xFFFF7B86))
                            Spacer(Modifier.height(8.dp))
                            Text(if (serviceActive) "סטטוס העוזר: פעיל ומאזין" else "סטטוס העוזר: לא פעיל", fontSize = 15.sp, color = if (serviceActive) Color(0xFF54E0C1) else Color(0xFFB9C7D6))
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
                                    permissionLauncher.launch(missing.toTypedArray())
                                } else {
                                    val currentPrefs = getSharedPreferences(VoiceAssistantService.PREFS, MODE_PRIVATE)
                                    if (enabled) {
                                        currentPrefs.edit().putBoolean(VoiceAssistantService.KEY_AUTO_START, false).apply()
                                        if (currentPrefs.getBoolean("service_active", false)) {
                                            val stopIntent = Intent(this@MainActivity, VoiceAssistantService::class.java).apply { action = VoiceAssistantService.ACTION_STOP }
                                            startService(stopIntent)
                                        }
                                        enabled = false
                                    } else {
                                        currentPrefs.edit().putBoolean(VoiceAssistantService.KEY_AUTO_START, true).apply()
                                        val startIntent = Intent(this@MainActivity, VoiceAssistantService::class.java).apply {
                                            action = VoiceAssistantService.ACTION_START
                                            putExtra(VoiceAssistantService.EXTRA_LANGUAGE, "en-US")
                                        }
                                        ContextCompat.startForegroundService(this@MainActivity, startIntent)
                                        enabled = true
                                    }
                                }
                            }) { Text(if (enabled) "כבה את העוזר" else "הפעל האזנה רציפה") }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("מצב החיבור מתעדכן כשאירוע חיבור או ניתוק מתקבל. יש לבדוק את ההתנהגות בפועל במכשיר.", color = Color(0xFF718096), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}