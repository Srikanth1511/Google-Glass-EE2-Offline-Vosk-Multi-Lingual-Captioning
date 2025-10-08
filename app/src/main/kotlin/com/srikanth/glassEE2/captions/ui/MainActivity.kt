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
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
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
    private lateinit var captionScroll: ScrollView
    private lateinit var recStateChip: TextView
    private lateinit var languageChip: TextView
    private lateinit var batteryChip: TextView

    private val currentTranscript = StringBuilder()
    private val REQ_AUDIO = 1001
    private val batteryHandler = Handler(Looper.getMainLooper())

    // For rolling transcription
    private var lastPartialText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make fullscreen - hide notification bar and navigation
        makeFullscreen()

        setContentView(R.layout.activity_main)

        captionTextView = findViewById(R.id.captionTextView)
        captionScroll = findViewById(R.id.captionScroll)
        recStateChip = findViewById(R.id.recStateChip)
        languageChip = findViewById(R.id.languageChip)
        batteryChip = findViewById(R.id.batteryChip)

        gestureDetector = GestureDetector(this, this).apply {
            setOnDoubleTapListener(this@MainActivity)
        }

        // Make root layout handle touches
        val rootLayout = findViewById<View>(R.id.rootLayout)
        rootLayout.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        // Make ScrollView pass touch events to Activity
        captionScroll.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // Return false to allow scrolling to still work
        }

        // Make caption text view also handle touches
        captionTextView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        // Also add click listener to status bar as backup
        val statusLayout = findViewById<View>(R.id.statusLayout)
        statusLayout.setOnClickListener {
            Log.d("MainActivity", "Status bar clicked!")
            if (isRecording) pauseCaptions() else startCaptions()
        }

        // Listen for transcript updates
        registerReceiver(captionReceiver, IntentFilter(CaptionService.ACTION_TRANSCRIPT_UPDATE))

        // Ask for mic permission
        ensureMicPermission()

        // Start battery monitoring
        startBatteryMonitoring()
    }

    private fun makeFullscreen() {
        // Hide notification bar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Hide navigation bar and make immersive
        if (Build.VERSION.SDK_INT >= 19) {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }

        // Remove action bar
        supportActionBar?.hide()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            makeFullscreen() // Re-apply when user returns to app
        }
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

            // Skip status messages
            if (text.startsWith("⏳") || text.startsWith("✅") ||
                text.startsWith("❌") || text.startsWith("🎙️") ||
                text.startsWith("⏸️") || text.startsWith("⏹️")) {
                return
            }

            // Continuous rolling transcription
            if (isRecording && text.isNotBlank()) {
                // If text is different from last partial, it's a new/updated phrase
                if (text != lastPartialText) {
                    // Remove the last partial text and add the new one
                    if (lastPartialText.isNotEmpty() && currentTranscript.endsWith(lastPartialText)) {
                        // Remove last partial
                        val len = lastPartialText.length
                        currentTranscript.delete(currentTranscript.length - len, currentTranscript.length)
                    }

                    // Add new text
                    currentTranscript.append(text).append(" ")
                    lastPartialText = text

                    // Update display
                    captionTextView.text = currentTranscript.toString()

                    // Auto-scroll to bottom
                    captionScroll.post {
                        captionScroll.fullScroll(ScrollView.FOCUS_DOWN)
                    }
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
        Log.d("MainActivity", "Single tap detected!")
        if (!ensureMicPermission()) return true

        if (isRecording) {
            Log.d("MainActivity", "Pausing captions")
            pauseCaptions()
        } else {
            Log.d("MainActivity", "Starting captions")
            startCaptions()
        }
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
        Log.d("MainActivity", "startCaptions() called")
        val i = Intent(this, CaptionService::class.java)
            .setAction(CaptionService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        isRecording = true
        recStateChip.text = "REC"
        Log.d("MainActivity", "Set chip to REC, isRecording=$isRecording")
        captionTextView.text = "" // Clear on start
        currentTranscript.clear()
        lastPartialText = ""
    }

    private fun pauseCaptions() {
        Log.d("MainActivity", "pauseCaptions() called")
        val i = Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_PAUSE)
        startService(i)
        isRecording = false
        recStateChip.text = "PAUSED"
        Log.d("MainActivity", "Set chip to PAUSED, isRecording=$isRecording")
    }

    private fun stopCaptions() {
        Log.d("MainActivity", "stopCaptions() called")
        val i = Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP)
        startService(i)
        isRecording = false
        recStateChip.text = "READY"
        Log.d("MainActivity", "Set chip to READY, isRecording=$isRecording")
    }

    private fun saveTranscript() {
        if (currentTranscript.isEmpty()) return

        try {
            // Use app-specific external directory (no WRITE_EXTERNAL_STORAGE needed)
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(filesDir, "transcripts")

            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "transcript_$timestamp.txt")

            file.writeText(currentTranscript.toString().trim())

            Toast.makeText(this, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}