package com.srikanth.glassEE2.captions.stt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CaptionService : Service() {

    companion object {
        private const val TAG = "CaptionService"

        const val ACTION_START = "com.srikanth.glassEE2.captions.ACTION_START"
        const val ACTION_PAUSE = "com.srikanth.glassEE2.captions.ACTION_PAUSE"
        const val ACTION_STOP  = "com.srikanth.glassEE2.captions.ACTION_STOP"

        const val ACTION_TRANSCRIPT_UPDATE = "com.srikanth.glassEE2.captions.TRANSCRIPT_UPDATE"
        const val EXTRA_TRANSCRIPT = "extra_transcript"
        const val EXTRA_LANG = "extra_lang"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var prepJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val recording = AtomicBoolean(false)
    private val isReady = AtomicBoolean(false)
    private lateinit var asr: ASREngine
    private var lang = "en"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() called")

        try {
            startForegroundWithNotification()
            Log.d(TAG, "Foreground notification started")

            asr = ASREngine(this)
            Log.d(TAG, "ASREngine instance created")

            prepareModelAsync()
            Log.d(TAG, "Model preparation started")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate()", e)
            status("❌ Service init failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() action=${intent?.action}")

        try {
            intent?.getStringExtra(EXTRA_LANG)?.let {
                Log.d(TAG, "Language set to: $it")
                lang = it
            }

            if (!isReady.get()) {
                Log.d(TAG, "Model not ready, triggering preparation")
                prepareModelAsync()
            }

            when (intent?.action) {
                ACTION_START -> {
                    Log.i(TAG, "ACTION_START received")
                    startRecording()
                }
                ACTION_PAUSE -> {
                    Log.i(TAG, "ACTION_PAUSE received")
                    pauseRecording()
                }
                ACTION_STOP -> {
                    Log.i(TAG, "ACTION_STOP received")
                    stopRecording()
                }
                else -> {
                    Log.i(TAG, "No action or default action, starting recording")
                    startRecording()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand()", e)
            status("❌ Command failed: ${e.message}")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called")
        stopRecording()
        try {
            asr.close()
            Log.d(TAG, "ASR engine closed")
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing ASR", e)
        }
        super.onDestroy()
    }

    private fun prepareModelAsync() {
        if (prepJob != null) {
            Log.d(TAG, "Model preparation already in progress")
            return
        }

        prepJob = scope.launch {
            try {
                Log.i(TAG, "Starting async model preparation for lang=$lang")
                status("⏳ Preparing model ($lang)… First run may take a minute")

                asr.init(lang) { msg ->
                    Log.d(TAG, "Model init progress: $msg")
                    status(msg)
                }

                isReady.set(true)
                Log.i(TAG, "Model ready!")
                status("✅ Model ready")
            } catch (e: Exception) {
                Log.e(TAG, "Model init failed", e)
                status("❌ Model init failed: ${e.message}")
                isReady.set(false)
            } finally {
                prepJob = null
            }
        }
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = "captions_ch"

        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null) {
            Log.d(TAG, "Creating notification channel")
            nm.createNotificationChannel(
                NotificationChannel(ch , "Captions", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val n: Notification = NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Captions")
            .setContentText("Starting…")
            .setOngoing(true)
            .build()

        startForeground(1, n)
        Log.d(TAG, "Started foreground service with notification")
    }

    private fun startRecording() {
        if (recording.getAndSet(true)) {
            Log.d(TAG, "Already recording, ignoring startRecording()")
            return
        }

        Log.i(TAG, "Starting recording...")
        job = scope.launch {
            // wait until model ready
            while (!isReady.get() && recording.get()) {
                status("⏳ Preparing model…")
                delay(200)
            }

            if (!recording.get()) {
                Log.d(TAG, "Recording was cancelled before model ready")
                return@launch
            }

            Log.d(TAG, "Model ready, starting audio loop")
            audioLoop()
        }
    }

    private fun pauseRecording() {
        Log.i(TAG, "Pausing recording")
        recording.set(false)
        job?.cancel()
        stopAudio()
        status("⏸️ Paused")
    }

    private fun stopRecording() {
        Log.i(TAG, "Stopping recording")
        recording.set(false)
        job?.cancel()
        stopAudio()
        status("⏹️ Stopped")
        stopSelf()
    }

    private fun audioLoop() {
        val sr = 16_000
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = maxOf(min, 4096)

        Log.d(TAG, "AudioRecord buffer size: min=$min, using=$buf")

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            status("❌ Mic init failed: ${e.message}")
            return
        }

        audioRecord = rec

        try {
            Log.d(TAG, "Starting AudioRecord...")
            rec.startRecording()
            Log.i(TAG, "AudioRecord started, state=${rec.recordingState}")
        } catch (e: Throwable) {
            Log.e(TAG, "AudioRecord start failed", e)
            status("❌ Mic start failed: ${e.message}")
            return
        }

        status("🎙️ Listening")
        val bytes = ByteArray(buf)
        var frameCount = 0

        while (recording.get()) {
            val n = rec.read(bytes, 0, bytes.size)

            if (n > 0) {
                frameCount++
                if (frameCount % 100 == 0) {
                    Log.v(TAG, "Processed $frameCount audio frames")
                }

                asr.acceptPcm16(bytes, n)?.let { text ->
                    Log.d(TAG, "Got transcript: $text")
                    status(text)
                }
            } else if (n < 0) {
                Log.w(TAG, "AudioRecord.read() returned error: $n")
            }
        }

        Log.d(TAG, "Audio loop exited after $frameCount frames")
    }

    private fun stopAudio() {
        audioRecord?.run {
            try {
                stop()
                Log.d(TAG, "AudioRecord stopped")
            } catch (e: Throwable) {
                Log.e(TAG, "Error stopping AudioRecord", e)
            }
            release()
            Log.d(TAG, "AudioRecord released")
        }
        audioRecord = null
    }

    private fun status(text: String) {
        Log.d(TAG, "Status update: $text")
        sendBroadcast(Intent(ACTION_TRANSCRIPT_UPDATE).putExtra(EXTRA_TRANSCRIPT, text))
    }
}