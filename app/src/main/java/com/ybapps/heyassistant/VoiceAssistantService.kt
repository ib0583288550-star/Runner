package com.ybapps.heyassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class VoiceAssistantService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private var waitingForCommand = false
    private var language = "en-US"
    private var statusText = "Waiting for Hey Assistant"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_LANGUAGE)?.let { language = it }
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startListening()
            }
        }
        return START_STICKY
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition is unavailable")
            stopSelf()
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            updateNotification("On-device speech recognition is unavailable")
            stopSelf()
            return
        }
        recognizer?.destroy()
        recognizer = if (Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { updateNotification(if (waitingForCommand) "Listening for a command…" else "Waiting for Hey Assistant") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { restartListening() }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().lowercase(Locale.ROOT)
                processRecognizedText(text)
                restartListening()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().lowercase(Locale.ROOT)
                if (!waitingForCommand && containsWakePhrase(text)) {
                    waitingForCommand = true
                    updateNotification("Wake phrase detected — say a command")
                    try { recognizer?.cancel() } catch (_: Exception) { }
                    restartListening()
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        restartListening()
    }

    private fun processRecognizedText(text: String) {
        if (!waitingForCommand && containsWakePhrase(text)) {
            waitingForCommand = true
            updateNotification("מילת ההפעלה זוהתה — אמור פקודה")
            return
        }
        if (!waitingForCommand || text.isBlank()) return
        val normalized = text.replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        val action = when {
            normalized.contains("play music") -> MediaAction.PLAY
            normalized.contains("stop music") -> MediaAction.STOP
            normalized.contains("next song") -> MediaAction.NEXT
            normalized.contains("previous song") -> MediaAction.PREVIOUS
            normalized.contains("volume up") -> MediaAction.VOLUME_UP
            normalized.contains("volume down") -> MediaAction.VOLUME_DOWN
            else -> null
        }
        if (action == null) {
            updateNotification("Command not recognized")
        } else {
            executeAction(action)
        }
        waitingForCommand = false
    }

    private fun containsWakePhrase(text: String): Boolean =
        text.contains("hey assistant")

    private enum class MediaAction { PLAY, STOP, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }

    private fun executeAction(action: MediaAction) {
        when (action) {
            MediaAction.PLAY -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); updateNotification("Playing music") }
            MediaAction.STOP -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP); updateNotification("Stopping music") }
            MediaAction.NEXT -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); updateNotification("Next song") }
            MediaAction.PREVIOUS -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); updateNotification("Previous song") }
            MediaAction.VOLUME_UP -> {
                (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                updateNotification("Volume increased")
            }
            MediaAction.VOLUME_DOWN -> {
                (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                updateNotification("Volume decreased")
            }
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audio.dispatchMediaKeyEvent(down)
        audio.dispatchMediaKeyEvent(up)
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

    private fun stopListening() {
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
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

    override fun onDestroy() { stopListening(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_LANGUAGE = "recognition_language"
        const val ACTION_START = "com.ybapps.heyassistant.START"
        const val ACTION_STOP = "com.ybapps.heyassistant.STOP"
        private const val CHANNEL_ID = "voice_assistant"
        private const val NOTIFICATION_ID = 41
    }
}
