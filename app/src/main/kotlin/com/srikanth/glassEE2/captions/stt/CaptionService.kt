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
        startForegroundWithNotification()
        asr = ASREngine(this)
        prepareModelAsync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_LANG)?.let { lang = it }
        if (!isReady.get()) prepareModelAsync()

        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_STOP  -> stopRecording()
            else         -> startRecording()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        try { asr.close() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun prepareModelAsync() {
        if (prepJob != null) return
        prepJob = scope.launch {
            try {
                status("⏳ Preparing model ($lang)… First run may take a minute")
                asr.init(lang) { msg -> status(msg) }  // <— progress during copy
                isReady.set(true)
                status("✅ Model ready")
            } catch (e: Exception) {
                status("❌ Model init failed: ${e.message}")
            } finally {
                prepJob = null
            }
        }
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = "captions_ch"
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null) {
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
    }

    private fun startRecording() {
        if (recording.getAndSet(true)) return
        job = scope.launch {
            // wait until model ready
            while (!isReady.get() && recording.get()) {
                status("⏳ Preparing model…")
                delay(200)
            }
            if (!recording.get()) return@launch
            audioLoop()
        }
    }

    private fun pauseRecording() {
        recording.set(false)
        job?.cancel()
        stopAudio()
        status("⏸️ Paused")
    }

    private fun stopRecording() {
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
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf
        )
        audioRecord = rec
        try {
            rec.startRecording()
        } catch (e: Throwable) {
            Log.e("CaptionService", "AudioRecord start failed", e)
            status("❌ Mic start failed: ${e.message}")
            return
        }

        status("🎙️ Listening")
        val bytes = ByteArray(buf)
        while (recording.get()) {
            val n = rec.read(bytes, 0, bytes.size)
            if (n > 0) {
                asr.acceptPcm16(bytes, n)?.let { text ->
                    status(text)
                }
            }
        }
    }

    private fun stopAudio() {
        audioRecord?.run {
            try { stop() } catch (_: Throwable) {}
            release()
        }
        audioRecord = null
    }

    private fun status(text: String) {
        sendBroadcast(Intent(ACTION_TRANSCRIPT_UPDATE).putExtra(EXTRA_TRANSCRIPT, text))
    }
}
