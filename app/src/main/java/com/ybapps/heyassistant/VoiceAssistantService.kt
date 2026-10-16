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
    private var language = "en-US"
    private var statusText = "Waiting for voice command"
    private var serviceStopping = false
    private var legacyScoStarted = false
    private var routeReadyDelayMs = 1200L
    private var selectedCommunicationDeviceId: Int? = null
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
            updateNotification("Speech recognition is unavailable on this device")
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_active", false).apply()
            stopSelf()
            return
        }
        if (recognizer != null) {
            scheduleRestart(250)
            return
        }
        serviceStopping = false
        if (!prepareBluetoothMicrophone()) {
            updateNotification("Bluetooth microphone unavailable. Connect a headset with a microphone and restart.")
            return
        }
        createRecognizer()
        scheduleRestart(routeReadyDelayMs)
    }

    private fun createRecognizer() {
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { updateNotification("Listening in English") }
            override fun onBeginningOfSpeech() { updateNotification("Hearing speech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { updateNotification("Processing command") }
            override fun onError(error: Int) {
                if (serviceStopping) return
                val detail = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "Recognition service reset"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network error"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No command recognized"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    else -> "Speech recognition error $error"
                }
                updateNotification("$detail — restarting listener")
                // Recreate only the speech recognizer; do not toggle the Bluetooth audio route.
                handler.removeCallbacks(restartTask)
                handler.postDelayed({
                    if (!serviceStopping) {
                        createRecognizer()
                        scheduleRestart(500)
                    }
                }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 600)
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().lowercase(Locale.ROOT)
                if (spoken.isNotBlank()) processRecognizedText(spoken)
                if (!serviceStopping) scheduleRestart(250)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) updateNotification("Recognizing: ${partial.take(70)}")
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun prepareBluetoothMicrophone(): Boolean {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            routeReadyDelayMs = 1200L
            selectedCommunicationDeviceId = null
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
                updateNotification("Bluetooth microphone selected: " + bluetoothDevice.productName)
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
            updateNotification("Connecting Bluetooth microphone")
            routeReadyDelayMs = 1600L
            return true
        } catch (e: Exception) {
            updateNotification("Bluetooth microphone setup failed: ${e.message ?: "unknown error"}")
            try { audio.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
            return false
        }
    }

    private fun releaseBluetoothMicrophone() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
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
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 30000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 30000L)
            }
            r.startListening(intent)
        } catch (_: Exception) {
            if (!serviceStopping) {
                handler.postDelayed({
                    if (!serviceStopping) {
                        createRecognizer()
                        scheduleRestart(500)
                    }
                }, 700)
            }
        }
    }

    private fun processRecognizedText(text: String) {
        if (text.isBlank()) return
        val normalized = text.replace(Regex("[^\\\\p{L}\\\\p{N} ]"), " ")
            .replace(Regex("\\\\s+"), " ").trim()
        if (normalized.contains("stop assistant")) {
            serviceStopping = true
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_START, false).putBoolean("service_active", false).apply()
            updateNotification("Assistant stopped")
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val action = when {
            normalized.contains("play music") -> MediaAction.PLAY
            normalized.contains("stop music") -> MediaAction.STOP
            normalized.contains("next song") -> MediaAction.NEXT
            normalized.contains("previous song") -> MediaAction.PREVIOUS
            normalized.contains("volume up") -> MediaAction.VOLUME_UP
            normalized.contains("volume down") -> MediaAction.VOLUME_DOWN
            else -> null
        }
        if (action == null) updateNotification("Unrecognized command: ${text.take(65)}")
        else executeAction(action)
    }

    private enum class MediaAction { PLAY, STOP, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }

    private fun executeAction(action: MediaAction) {
        when (action) {
            MediaAction.PLAY -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); updateNotification("Playing music") }
            MediaAction.STOP -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP); updateNotification("Stopping music") }
            MediaAction.NEXT -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); updateNotification("Next song") }
            MediaAction.PREVIOUS -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); updateNotification("Previous song") }
            MediaAction.VOLUME_UP -> {
                (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                updateNotification("Volume up")
            }
            MediaAction.VOLUME_DOWN -> {
                (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                updateNotification("Volume down")
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
        return builder.setContentTitle("Hey Assistant")
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
