package com.srikanth.glassEE2.captions.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

class MainActivity :
    AppCompatActivity(),
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener {

    private lateinit var gestureDetector: GestureDetector
    private var isRecording = false

    private lateinit var captionTextView: TextView
    private lateinit var recStateChip: TextView
    private lateinit var batteryChip: TextView

    private val REQ_AUDIO = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        captionTextView = findViewById(R.id.captionTextView)
        recStateChip     = findViewById(R.id.recStateChip)
        batteryChip      = findViewById(R.id.batteryChip)

        gestureDetector = GestureDetector(this, this).apply {
            setOnDoubleTapListener(this@MainActivity)
        }

        // listen for status / transcript updates
        registerReceiver(captionReceiver, IntentFilter(CaptionService.ACTION_TRANSCRIPT_UPDATE))

        // ask mic permission early
        ensureMicPermission()

        // tiny hint
        Toast.makeText(this, "Tap to start • Long-press to stop", Toast.LENGTH_SHORT).show()

        // dummy battery updater
        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                batteryChip.text = "--%"
                Handler(Looper.getMainLooper()).postDelayed(this, 60_000)
            }
        })
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
            Toast.makeText(this, "Mic permission granted", Toast.LENGTH_SHORT).show()
        } else if (code == REQ_AUDIO) {
            Toast.makeText(this, "Mic permission needed for captions", Toast.LENGTH_LONG).show()
        }
    }

    private val captionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(CaptionService.EXTRA_TRANSCRIPT) ?: return
            captionTextView.text = text
        }
    }

    override fun onDestroy() {
        unregisterReceiver(captionReceiver)
        super.onDestroy()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (gestureDetector.onTouchEvent(event)) true else super.onTouchEvent(event)

    // Gestures
    override fun onSingleTapUp(e: MotionEvent?): Boolean {
        if (!ensureMicPermission()) return true
        if (isRecording) pauseCaptions() else startCaptions()
        return true
    }

    override fun onLongPress(e: MotionEvent?) {
        stopCaptions()
        if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask() else finish()
    }

    override fun onDown(e: MotionEvent?): Boolean = true
    override fun onShowPress(e: MotionEvent?) {}
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, dx: Float, dy: Float): Boolean = false
    override fun onFling(e1: MotionEvent?, e2: MotionEvent?, vx: Float, vy: Float): Boolean {
        if (e1 != null && e2 != null && (e2.y - e1.y) > 100f) {
            stopCaptions()
            if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask() else finish()
            return true
        }
        return false
    }
    override fun onDoubleTap(e: MotionEvent?): Boolean = false
    override fun onDoubleTapEvent(e: MotionEvent?): Boolean = false
    override fun onSingleTapConfirmed(e: MotionEvent?): Boolean = false

    private fun startCaptions() {
        val i = Intent(this, CaptionService::class.java)
            .setAction(CaptionService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        isRecording = true
        recStateChip.text = "REC"
        captionTextView.text = "⏳ Preparing model… (first run may take a bit)"
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
    }
}
