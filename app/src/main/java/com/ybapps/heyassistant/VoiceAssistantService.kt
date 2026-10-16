package com.ybapps.heyassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class VoiceAssistantService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private var language = "he-IL"
    private var statusText = "ממתין לפקודה קולית"
    private var serviceStopping = false
    private var useHebrew = false
    private var legacyScoStarted = false
    private var routeReadyDelayMs = 1200L
    private var selectedCommunicationDeviceId: Int? = null
    private var routeWaitAttempts = 0
    private var routeRetryTask = Runnable { if (!serviceStopping) startListening() }
    private val handler = Handler(Looper.getMainLooper())
    private val restartTask = Runnable { startRecognitionSession() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_LANGUAGE)?.let { language = it }
        when (intent?.action) {
            ACTION_STOP, ACTION_HEADPHONES_DISCONNECTED -> {
                serviceStopping = true
                val edit = getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_active", false)
                if (intent.action == ACTION_STOP) edit.putBoolean(KEY_AUTO_START, false)
                edit.apply()
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                serviceStopping = false
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_AUTO_START, true).putBoolean("service_active", true).apply()
                startForeground(NOTIFICATION_ID, buildNotification())
                startListening()
            }
        }
        return START_STICKY
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("אין שירות זיהוי דיבור זמין במכשיר")
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_active", false).apply()
            stopSelf()
            return
        }
        stopListening()
        serviceStopping = false
        if (!prepareBluetoothMicrophone()) {
            updateNotification("שגיאה: לא ניתן לנתב למיקרופון Bluetooth. בדוק שהאוזנייה מחוברת ותומכת במיקרופון.")
            handler.removeCallbacks(routeRetryTask)
            handler.postDelayed(routeRetryTask, 2500)
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                updateNotification(if (useHebrew) "מאזין בעברית — אמור פקודה" else "Listening in English — say a command")
            }
            override fun onBeginningOfSpeech() { updateNotification("קולט דיבור…") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { updateNotification("מעבד את הפקודה…") }
            override fun onError(error: Int) {
                if (!serviceStopping) {
                    val detail = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "שגיאת שמע"
                        SpeechRecognizer.ERROR_CLIENT -> "שגיאת שירות זיהוי"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "אין הרשאת מיקרופון"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "שגיאת רשת בזיהוי דיבור"
                        SpeechRecognizer.ERROR_NO_MATCH -> "לא זוהתה פקודה"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "שירות הזיהוי תפוס"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "לא נשמע דיבור"
                        else -> "שגיאת זיהוי $error"
                    }
                    updateNotification("$detail — מנסה להמשיך להאזין")
                    try { recognizer?.cancel() } catch (_: Exception) {}
                    scheduleRestart(700)
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().lowercase(Locale.ROOT)
                if (text.isNotBlank()) processRecognizedText(text)
                if (!serviceStopping) scheduleRestart(350)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) updateNotification("זיהוי: ${partial.take(70)}")
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        scheduleRestart(routeReadyDelayMs)
    }

    private fun prepareBluetoothMicrophone(): Boolean {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            routeReadyDelayMs = 1200L
            selectedCommunicationDeviceId = null
            routeWaitAttempts = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bluetoothDevice = audio.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (bluetoothDevice == null || !audio.setCommunicationDevice(bluetoothDevice)) {
                    audio.clearCommunicationDevice()
                    audio.mode = AudioManager.MODE_NORMAL
                    return false
                }
                selectedCommunicationDeviceId = bluetoothDevice.id
                updateNotification("נבחר התקן Bluetooth: " + bluetoothDevice.productName + " — מוודא ניתוב מיקרופון…")
                return true
            }
            val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val bluetoothConnected = outputs.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            if (!bluetoothConnected) {
                audio.mode = AudioManager.MODE_NORMAL
                return false
            }
            audio.startBluetoothSco()
            audio.isBluetoothScoOn = true
            legacyScoStarted = true
            updateNotification("מתחבר למיקרופון Bluetooth…")
            routeReadyDelayMs = 1600L
            return true
        } catch (e: Exception) {
            updateNotification("שגיאה בהפעלת מיקרופון Bluetooth: ${e.message ?: "לא ידוע"}")
            try { audio.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
            return false
        }
    }

    private fun releaseBluetoothMicrophone() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
            selectedCommunicationDeviceId = null
            routeWaitAttempts = 0
            if (legacyScoStarted) {
                audio.isBluetoothScoOn = false
                audio.stopBluetoothSco()
                legacyScoStarted = false
            }
            audio.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    private fun scheduleRestart(delayMs: Long) {
        handler.removeCallbacks(restartTask)
        handler.postDelayed(restartTask, delayMs)
    }

    private fun startRecognitionSession() {
        if (serviceStopping) return
        val r = recognizer ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            val expectedId = selectedCommunicationDeviceId
            val activeDevice = audio.communicationDevice
            if (expectedId == null || activeDevice?.id != expectedId) {
                routeWaitAttempts++
                if (routeWaitAttempts >= 40) {
                    updateNotification("שגיאה: Android לא אישר ניתוב לאוזניות Bluetooth. לא הופעל מיקרופון הטלפון. נתק וחבר מחדש את האוזניות.")
                    return
                }
                updateNotification("ממתין לניתוב מיקרופון Bluetooth… (" + routeWaitAttempts + "/40)")
                scheduleRestart(500)
                return
            }
            routeWaitAttempts = 0
            updateNotification("מיקרופון Bluetooth פעיל — מאזין")
        }
        val sessionLanguage = if (useHebrew) "he-IL" else "en-US"
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, sessionLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, sessionLanguage)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 30000L)
            }
            r.startListening(intent)
        } catch (_: Exception) {
            if (!serviceStopping) scheduleRestart(1200)
        }
    }

    private fun processRecognizedText(text: String) {
        if (text.isBlank()) return
        val normalized = text.replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (normalized.contains("stop assistant") || normalized.contains("עצור את העוזר") || normalized.contains("תעצור את העוזר")) {
            serviceStopping = true
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_START, false).putBoolean("service_active", false).apply()
            updateNotification("העוזר הופסק")
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val action = when {
            normalized.contains("play music") || normalized.contains("נגן מוזיקה") || normalized.contains("תנגן מוזיקה") || normalized.contains("הפעל מוזיקה") -> MediaAction.PLAY
            normalized.contains("stop music") || normalized.contains("עצור מוזיקה") || normalized.contains("תעצור מוזיקה") -> MediaAction.STOP
            normalized.contains("next song") || normalized.contains("השיר הבא") || normalized.contains("שיר הבא") -> MediaAction.NEXT
            normalized.contains("previous song") || normalized.contains("השיר הקודם") || normalized.contains("שיר קודם") -> MediaAction.PREVIOUS
            normalized.contains("volume up") || normalized.contains("הגבר קול") || normalized.contains("תגביר קול") || normalized.contains("הגבר את הקול") -> MediaAction.VOLUME_UP
            normalized.contains("volume down") || normalized.contains("הנמך קול") || normalized.contains("תנמיך קול") || normalized.contains("הנמך את הקול") -> MediaAction.VOLUME_DOWN
            else -> null
        }
        if (action == null) {
            updateNotification("לא זוהתה פקודה: ${text.take(65)}")
        } else {
            executeAction(action)
        }
    }

    private enum class MediaAction { PLAY, STOP, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }

    private fun executeAction(action: MediaAction) {
        when (action) {
            MediaAction.PLAY -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); updateNotification("מפעיל מוזיקה") }
            MediaAction.STOP -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP); updateNotification("עוצר מוזיקה") }
            MediaAction.NEXT -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); updateNotification("השיר הבא") }
            MediaAction.PREVIOUS -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); updateNotification("השיר הקודם") }
            MediaAction.VOLUME_UP -> {
                (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                updateNotification("הווליום הוגבר")
            }
            MediaAction.VOLUME_DOWN -> {
                (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                updateNotification("הווליום הונמך")
            }
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun stopListening() {
        handler.removeCallbacks(restartTask)
        handler.removeCallbacks(routeRetryTask)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        releaseBluetoothMicrophone()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Voice listening", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("היי עוזר")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        statusText = text.take(120)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        handler.removeCallbacks(restartTask)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_active", false).apply()
        stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_LANGUAGE = "recognition_language"
        const val ACTION_START = "com.ybapps.heyassistant.START"
        const val ACTION_STOP = "com.ybapps.heyassistant.STOP"
        const val ACTION_HEADPHONES_DISCONNECTED = "com.ybapps.heyassistant.HEADPHONES_DISCONNECTED"
        const val PREFS = "assistant_settings"
        const val KEY_AUTO_START = "auto_start_on_headphones"
        private const val CHANNEL_ID = "voice_assistant"
        private const val NOTIFICATION_ID = 41
    }
}
