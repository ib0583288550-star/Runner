package com.ybapps.heyassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class VoiceAssistantService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private var waitingForCommand = false
    private var language = "he-IL"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopListening(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startListening()
            }
        }
        return START_STICKY
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("זיהוי דיבור אינו זמין במכשיר")
            stopSelf()
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            updateNotification("אין במכשיר מנוע זיהוי מקומי זמין")
            stopSelf()
            return
        }
        recognizer?.destroy()
        recognizer = if (Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { updateNotification(if (waitingForCommand) "מקשיב לפקודה…" else "ממתין ל״היי עוזר״") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { restartListening() }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().lowercase(Locale.ROOT)
                if (!waitingForCommand && (text.contains("היי עוזר") || text.contains("hey assistant"))) {
                    waitingForCommand = true
                    updateNotification("זוהתה מילת ההפעלה — אמור פקודה")
                } else if (waitingForCommand && text.isNotBlank()) {
                    handleCommand(text)
                    waitingForCommand = false
                }
                restartListening()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().lowercase(Locale.ROOT)
                if (!waitingForCommand && (text.contains("היי עוזר") || text.contains("hey assistant"))) {
                    waitingForCommand = true
                    updateNotification("זוהתה מילת ההפעלה — אמור פקודה")
                    restartListening()
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        restartListening()
    }

    private fun restartListening() {
        val r = recognizer ?: return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            r.cancel()
            r.startListening(intent)
        } catch (_: Exception) { }
    }

    private fun handleCommand(command: String) {
        when {
            command.contains("עצור") || command.contains("stop listening") -> {
                updateNotification("הפקודה התקבלה: עצור האזנה")
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            command.contains("מה השעה") || command.contains("what time") -> {
                val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
                updateNotification("השעה $time")
            }
            command.contains("פתח הגדרות") || command.contains("open settings") -> {
                updateNotification("כדי לפתוח הגדרות, יש להפעיל זאת מתוך האפליקציה")
            }
            else -> updateNotification("לא הוגדרה עדיין פקודה: $command")
        }
    }

    private fun stopListening() {
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "האזנה קולית", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("היי עוזר")
            .setContentText("ממתין ל״היי עוזר״")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification().also {
            // Keep the notification stable; the detailed status is visible in the app in a later iteration.
        })
    }

    override fun onDestroy() { stopListening(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.ybapps.heyassistant.START"
        const val ACTION_STOP = "com.ybapps.heyassistant.STOP"
        private const val CHANNEL_ID = "voice_assistant"
        private const val NOTIFICATION_ID = 41
    }
}
