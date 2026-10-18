package com.ybapps.heyassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class VoiceAssistantService : Service() {
    @Volatile private var stopping = false
    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var model: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var statusText = "Ready"
    private var legacyScoStarted = false
    private var selectedCommunicationDeviceId: Int? = null
    private val lock = Any()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP, ACTION_HEADPHONES_DISCONNECTED -> {
                stopping = true
                val preferences = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("service_active", false)
                if (action == ACTION_STOP) preferences.putBoolean(KEY_AUTO_START, false)
                preferences.apply()
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (recording) return START_STICKY
                stopping = false
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_AUTO_START, true)
                    .putBoolean("service_active", true)
                    .apply()
                startForeground(NOTIFICATION_ID, buildNotification())
                thread(name = "hey-assistant-start") { initializeAndListen() }
            }
        }
        return START_STICKY
    }

    private fun initializeAndListen() {
        try {
            setStatus("Preparing English voice recognition")
            val modelDir = File(filesDir, MODEL_DIR)
            if (!File(modelDir, "am").exists()) {
                setStatus("Installing bundled English voice model")
                copyBundledModel(MODEL_DIR, modelDir)
            }
            if (stopping) return
            model = Model(modelDir.absolutePath)
            voskRecognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            if (!prepareBluetoothMicrophone()) {
                setStatus("Bluetooth microphone not available. Connect the headset and try again.")
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_active", false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("AudioRecord buffer setup failed: $minBuffer")
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, SAMPLE_RATE)
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val btInput = findBluetoothInput()
                if (btInput == null || !recorder.setPreferredDevice(btInput)) {
                    recorder.release()
                    throw IllegalStateException("Android did not expose the Bluetooth headset microphone")
                }
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException("Could not initialize Bluetooth audio capture")
            }
            audioRecord = recorder
            recorder.startRecording()
            recording = true
            setStatus("Listening continuously through Bluetooth headset")
            val buffer = ShortArray(2048)
            while (!stopping && recording) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val activeRecognizer = voskRecognizer ?: break
                if (activeRecognizer.acceptWaveForm(buffer, count)) {
                    val json = JSONObject(activeRecognizer.result)
                    val phrase = json.optString("text", "").trim()
                    if (phrase.isNotBlank()) processRecognizedText(phrase)
                }
            }
        } catch (e: Exception) {
            if (!stopping) {
                setStatus("Voice assistant error: ${e.message ?: e.javaClass.simpleName}")
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_active", false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } finally {
            stopCapture()
        }
    }

    private fun copyBundledModel(assetPath: String, target: File) {
        if (stopping) throw InterruptedException("Stopped")
        target.deleteRecursively()
        target.mkdirs()
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            return
        }
        for (child in children) {
            if (stopping) throw InterruptedException("Stopped")
            val childAssetPath = "$assetPath/$child"
            val childTarget = File(target, child)
            val nested = assets.list(childAssetPath) ?: emptyArray()
            if (nested.isNotEmpty()) {
                copyBundledModel(childAssetPath, childTarget)
            } else {
                childTarget.parentFile?.mkdirs()
                assets.open(childAssetPath).use { input ->
                    FileOutputStream(childTarget).use { output -> input.copyTo(output) }
                }
            }
        }
        if (!File(target, "am").exists()) {
            throw IllegalStateException("Bundled English voice model is missing or incomplete")
        }
    }

    private fun prepareBluetoothMicrophone(): Boolean {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val device = audio.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                } ?: return false
                if (!audio.setCommunicationDevice(device)) return false
                selectedCommunicationDeviceId = device.id
            } else {
                val hasBluetooth = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
                if (!hasBluetooth) return false
                audio.startBluetoothSco()
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = true
                legacyScoStarted = true
            }
            var input: AudioDeviceInfo? = null
            for (attempt in 0 until 12) {
                input = findBluetoothInput()
                if (input != null || stopping) break
                Thread.sleep(250)
            }
            return input != null
        } catch (e: Exception) {
            setStatus("Bluetooth microphone setup failed: ${e.message ?: "unknown error"}")
            return false
        }
    }

    private fun findBluetoothInput(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        return audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    private fun processRecognizedText(text: String) {
        val normalized = text.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return
        if ("stop assistant" in normalized) {
            stopping = true
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_START, false).putBoolean("service_active", false).apply()
            setStatus("Assistant stopped")
            Handler(Looper.getMainLooper()).post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        when {
            "play music" in normalized -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); setStatus("Playing music") }
            "stop music" in normalized -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP); setStatus("Stopping music") }
            "next song" in normalized -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); setStatus("Next song") }
            "previous song" in normalized || "last song" in normalized -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); setStatus("Previous song")
            }
            "volume up" in normalized -> {
                (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0
                )
                setStatus("Volume up")
            }
            "volume down" in normalized -> {
                (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0
                )
                setStatus("Volume down")
            }
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun stopCapture() {
        synchronized(lock) {
            recording = false
            try { audioRecord?.stop() } catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
            try { voskRecognizer?.close() } catch (_: Exception) {}
            voskRecognizer = null
            try { model?.close() } catch (_: Exception) {}
            model = null
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
                if (legacyScoStarted) {
                    @Suppress("DEPRECATION")
                    run { audio.isBluetoothScoOn = false }
                    audio.stopBluetoothSco()
                    legacyScoStarted = false
                }
                audio.mode = AudioManager.MODE_NORMAL
            } catch (_: Exception) {}
        }
    }

    private fun setStatus(value: String) {
        statusText = value.take(120)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Voice listening", NotificationManager.IMPORTANCE_LOW)
            )
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

    override fun onDestroy() {
        stopping = true
        stopCapture()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_active", false).apply()
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
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "vosk-model-small-en-us-0.15"
            }
}
