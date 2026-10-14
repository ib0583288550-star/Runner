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
import androidx.compose.foundation.layout.Row
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var enabled by remember { mutableStateOf(false) }
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
                            Text(if (enabled) "העוזר פעיל" else "העוזר מוכן", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("Wake phrase: Hey Assistant", color = Color(0xFFB9C7D6), fontSize = 13.sp)
                            Spacer(Modifier.height(14.dp))
                            Text("הפקודות הקבועות", fontWeight = FontWeight.Bold, color = Color(0xFF54E0C1))
                            Spacer(Modifier.height(8.dp))
                            Text("Play Music")
                            Text("Stop Music")
                            Text("Next Song")
                            Text("Previous Song")
                            Text("Volume Up")
                            Text("Volume Down")
                            Text("Stop Assistant")
                            Spacer(Modifier.height(14.dp))
                            Text("Speech recognition: English (US), on-device", color = Color(0xFF8D9BAA), fontSize = 13.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                                    if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                                    val missing = permissions.filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                                    if (missing.isNotEmpty()) {
                                        permissionLauncher.launch(missing.toTypedArray())
                                    } else {
                                        val intent = Intent(this@MainActivity, VoiceAssistantService::class.java)
                                        intent.action = if (enabled) VoiceAssistantService.ACTION_STOP else VoiceAssistantService.ACTION_START
                                        intent.putExtra(VoiceAssistantService.EXTRA_LANGUAGE, "en-US")
                                        if (!enabled) ContextCompat.startForegroundService(this@MainActivity, intent) else startService(intent)
                                        enabled = !enabled
                                    }
                                }
                            ) { Text(if (enabled) "עצור האזנה" else "הפעל עוזר") }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("זיהוי קולי מקומי ופעולה ברקע נמצאים בפיתוח ובבדיקה.", color = Color(0xFF718096), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
