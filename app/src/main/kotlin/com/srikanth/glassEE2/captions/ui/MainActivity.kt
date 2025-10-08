package com.srikanth.glassEE2.captions.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.srikanth.glassEE2.captions.R
import com.srikanth.glassEE2.captions.stt.CaptionService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity :
    AppCompatActivity(),
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener {

    private lateinit var gestureDetector: GestureDetector
    private var isRecording = false

    private lateinit var captionTextView: TextView
    private lateinit var recStateChip: TextView
    private lateinit var languageChip: TextView
    private lateinit var batteryChip: TextView

    private val currentTranscript = StringBuilder()
    private val REQ_AUDIO = 1001
    private val batteryHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        captionTextView = findViewById(R.id.captionTextView)
        recStateChip     = findViewById(R.id.recStateChip)
        languageChip     = findViewById(R.id.languageChip)
        batteryChip      = findViewById(R.id.batteryChip)

        gestureDetector = GestureDetector(this, this).apply {
            setOnDoubleTapListener(this@MainActivity)
        }

        // Listen for transcript updates
        registerReceiver(captionReceiver, IntentFilter(CaptionService.ACTION_TRANSCRIPT_UPDATE))

        // Ask for mic permission
        ensureMicPermission()

        // Start battery monitoring
        startBatteryMonitoring()
    }

    private fun ensureMicPermission(): Boolean {
        val ok = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO
            )
        }
        return ok
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_AUDIO && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Ready to caption", Toast.LENGTH_SHORT).show()
        } else if (code == REQ_AUDIO) {
            Toast.makeText(this, "Mic permission required", Toast.LENGTH_LONG).show()
        }
    }

    private fun startBatteryMonitoring() {
        val updateBattery = object : Runnable {
            override fun run() {
                val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                batteryChip.text = "$level%"
                batteryHandler.postDelayed(this, 30_000) // Update every 30s
            }
        }
        batteryHandler.post(updateBattery)
    }

    private val captionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(CaptionService.EXTRA_TRANSCRIPT) ?: return

            // Update UI
            captionTextView.text = text

            // Append to transcript if recording
            if (isRecording && text.isNotBlank()) {
                // Only add text that looks like actual captions (not status messages)
                if (!text.startsWith("⏳") && !text.startsWith("✅") &&
                    !text.startsWith("❌") && !text.startsWith("🎙️")) {
                    currentTranscript.append(text).append(" ")
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(captionReceiver)
        batteryHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (gestureDetector.onTouchEvent(event)) true else super.onTouchEvent(event)

    // --- Gestures ---
    override fun onSingleTapUp(e: MotionEvent?): Boolean {
        if (!ensureMicPermission()) return true
        if (isRecording) pauseCaptions() else startCaptions()
        return true
    }

    override fun onLongPress(e: MotionEvent?) {
        saveTranscript()
        stopCaptions()
        if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask() else finish()
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent?, vx: Float, vy: Float): Boolean {
        // Swipe down to exit
        if (e1 != null && e2 != null && (e2.y - e1.y) > 100f) {
            saveTranscript()
            stopCaptions()
            if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask() else finish()
            return true
        }
        return false
    }

    override fun onDown(e: MotionEvent?): Boolean = true
    override fun onShowPress(e: MotionEvent?) {}
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, dx: Float, dy: Float): Boolean = false
    override fun onDoubleTap(e: MotionEvent?): Boolean = false
    override fun onDoubleTapEvent(e: MotionEvent?): Boolean = false
    override fun onSingleTapConfirmed(e: MotionEvent?): Boolean = false

    // --- Caption control ---
    private fun startCaptions() {
        val i = Intent(this, CaptionService::class.java)
            .setAction(CaptionService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        isRecording = true
        recStateChip.text = "REC"
        captionTextView.text = "Preparing model…"
    }

    private fun pauseCaptions() {
        val i = Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_PAUSE)
        startService(i)
        isRecording = false
        recStateChip.text = "PAUSED"
    }

    private fun stopCaptions() {
        val i = Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP)
        startService(i)
        isRecording = false
        recStateChip.text = "READY"
    }

    private fun saveTranscript() {
        if (currentTranscript.isEmpty()) return

        try {
            // Use app-specific external directory (no WRITE_EXTERNAL_STORAGE needed on API 19+)
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(filesDir, "transcripts")

            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "transcript_$timestamp.txt")

            file.writeText(currentTranscript.toString().trim())

            Toast.makeText(this, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()
            currentTranscript.clear()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}