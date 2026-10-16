package com.ybapps.ledremote
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlin.math.atan2
import kotlin.math.PI
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF080B14)
private val Panel = Color(0xFF121A2B)
private val Cyan = Color(0xFF45E5FF)
private val presets = listOf(
 "אדום" to Color(0xFFFF334F), "כתום" to Color(0xFFFF8A24), "צהוב" to Color(0xFFFFD83D),
 "ירוק" to Color(0xFF20E88A), "תכלת" to Color(0xFF23D5E8), "כחול" to Color(0xFF3478FF),
 "סגול" to Color(0xFF9A55FF), "ורוד" to Color(0xFFFF4FD8), "לבן" to Color.White
)
class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { LedRemoteApp { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() } }
 }
}
@Composable
fun LedRemoteApp(notify: (String)->Unit) {
 var mode by remember { mutableIntStateOf(0) }
 var selected by remember { mutableStateOf(Color(0xFF28D9FF)) }
 var brightness by remember { mutableFloatStateOf(.82f) }
 var power by remember { mutableStateOf(true) }
 var effect by remember { mutableStateOf("מעבר צבעים") }
 val effects = listOf("הבזק","הבזק איטי","מעבר צבעים","דילוג","חלק","מסיבה")
 MaterialTheme(colorScheme = darkColorScheme(primary=Cyan,secondary=Color(0xFFB45CFF),background=Bg,surface=Panel)) {
  Surface(Modifier.fillMaxSize(),color=Bg) {
   Column(Modifier.fillMaxSize().padding(horizontal=18.dp)) {
    Row(Modifier.fillMaxWidth().padding(top=18.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
     Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Cyan,Color(0xFF925BFF)))),contentAlignment=Alignment.Center) { Icon(Icons.Default.Lightbulb,null,tint=Bg,modifier=Modifier.size(28.dp)) }
     Spacer(Modifier.width(12.dp))
     Column(Modifier.weight(1f)) { Text("YB LED REMOTE",fontSize=20.sp,fontWeight=FontWeight.Black,color=Color.White); Text("שלט תאורת LED אוניברסלי",fontSize=12.sp,color=Color(0xFF9DAEC8)) }
     IconButton(onClick={notify("חיבור ZaZa בבדיקת תאימות")}) { Icon(Icons.Default.Settings,"הגדרות",tint=Color.LightGray) }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(4.dp)) {
     listOf("גלגל צבעים","שלט מקורי").forEachIndexed { i,label ->
      Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if(mode==i) Color(0xFF253750) else Color.Transparent).clickable { mode=i }.padding(vertical=12.dp),contentAlignment=Alignment.Center) { Text(label,color=if(mode==i) Cyan else Color(0xFFB6C2D5),fontWeight=FontWeight.SemiBold) }
     }
    }
    Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=12.dp).clip(RoundedCornerShape(18.dp)).background(Panel).padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
     Box(Modifier.size(42.dp).clip(CircleShape).background(if(power) selected else Color(0xFF343A48)),contentAlignment=Alignment.Center) { Icon(Icons.Default.Lightbulb,null,tint=Color.White) }
     Spacer(Modifier.width(12.dp))
     Column(Modifier.weight(1f)) { Text("תאורת LED",color=Color.White,fontWeight=FontWeight.Bold); Text("פרופיל אוניברסלי",color=Color(0xFF93A2BA),fontSize=12.sp) }
     Switch(checked=power,onCheckedChange={power=it;notify("מצב התצוגה השתנה; שידור עדיין לא מחובר")})
    }
    if(mode==0) {
     Text("בחר צבע",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
     Box(Modifier.fillMaxWidth().height(220.dp),contentAlignment=Alignment.Center) {
      Canvas(Modifier.size(205.dp)) {
       drawCircle(brush=Brush.sweepGradient(listOf(Color.Red,Color.Yellow,Color.Green,Color.Cyan,Color.Blue,Color.Magenta,Color.Red)),style=Stroke(width=32.dp.toPx(),cap=StrokeCap.Round))
       drawCircle(color=selected,radius=53.dp.toPx())
       drawCircle(color=Color.White.copy(alpha=.9f),radius=8.dp.toPx(),center=Offset(size.width/2,size.height/2))
      }
      Box(
       Modifier.size(205.dp).pointerInput(Unit) {
        detectTapGestures { tap ->
         val center = size.width / 2f
         val dx = tap.x - center
         val dy = tap.y - center
         val degrees = ((atan2(dy, dx) * 180.0 / PI) + 360.0) % 360.0
         selected = Color.hsv(degrees.toFloat(), 1f, 1f)
        }
       }
      )
     }
     Text("צבעים מהירים",color=Color(0xFFB7C4D8),fontSize=13.sp)
     Spacer(Modifier.height(8.dp))
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
      presets.forEach { (name,color) -> Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.clickable { selected=color; notify("נבחר הצבע: "+name) }) {
       Box(Modifier.size(27.dp).clip(CircleShape).background(color),contentAlignment=Alignment.Center) { if(selected==color) Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White)) }
       Spacer(Modifier.height(4.dp)); Text(name,color=Color(0xFF9EADC4),fontSize=9.sp)
      } }
     }
    } else {
     Text("שלט בסגנון פיזי",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=12.dp))
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
      RemoteButton("⏻",Color(0xFFFF3D55)){power=!power;notify("פקודת הפעלה; שידור עוד לא מחובר")}
      RemoteButton("FLASH",Color(0xFF29364D)){effect="הבזק"}
      RemoteButton("SMOOTH",Color(0xFF29364D)){effect="חלק"}
     }
     Spacer(Modifier.height(12.dp))
     LazyVerticalGrid(columns=GridCells.Fixed(3),modifier=Modifier.weight(1f),contentPadding=PaddingValues(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(9.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
      items(presets) { (name,color) -> Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Panel).clickable { selected=color; notify("נבחר הצבע: "+name) }.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
       Box(Modifier.size(29.dp).clip(CircleShape).background(color)); Spacer(Modifier.height(5.dp)); Text(name,color=Color.White,fontSize=12.sp)
      } }
      items(effects) { e -> Column(Modifier.clip(RoundedCornerShape(14.dp)).background(if(effect==e) Color(0xFF243B55) else Panel).clickable { effect=e;notify("נבחר אפקט: "+e) }.padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally) {
       Icon(Icons.Default.Bolt,null,tint=if(effect==e) Cyan else Color(0xFF9DAEC8)); Text(e,color=Color.White,fontSize=11.sp,textAlign=TextAlign.Center)
      } }
     }
    }
    Row(verticalAlignment=Alignment.CenterVertically) {
     Text("בהירות",color=Color.White,fontWeight=FontWeight.SemiBold)
     Slider(value=brightness,onValueChange={brightness=it},modifier=Modifier.weight(1f),colors=SliderDefaults.colors(thumbColor=Cyan,activeTrackColor=Cyan))
     Text((brightness*100).toInt().toString()+"%",color=Cyan,fontSize=12.sp)
    }
    Button(onClick={notify("ממשק מוכן; צריך לאמת שידור דרך מתאם ZaZa")},modifier=Modifier.fillMaxWidth().padding(top=4.dp,bottom=7.dp).height(49.dp),shape=RoundedCornerShape(15.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF1C3B55))) {
     Icon(Icons.Default.ColorLens,null,tint=Cyan); Spacer(Modifier.width(8.dp)); Text("החל שליטה",color=Color.White,fontWeight=FontWeight.Bold)
    }
    Text("yb apps • גרסה 0.1 • חיבור המתאם בבדיקה",modifier=Modifier.fillMaxWidth().padding(bottom=8.dp),color=Color(0xFF62728A),fontSize=10.sp,textAlign=TextAlign.Center)
   }
  }
 }
}
@Composable
private fun RemoteButton(label:String,color:Color,action:()->Unit) {
 Box(Modifier.clip(RoundedCornerShape(16.dp)).background(color).clickable { action() }.padding(horizontal=12.dp,vertical=14.dp),contentAlignment=Alignment.Center) {
  Text(label,color=Color.White,fontWeight=FontWeight.Bold,fontSize=if(label.length>4) 10.sp else 22.sp)
 }
}
