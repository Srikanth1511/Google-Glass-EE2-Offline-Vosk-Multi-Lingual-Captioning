package com.srikanth.glassEE2.captions.stt

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

class ASREngine(private val context: Context) {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var currentLang = "en"
    private val sampleRate = 16000.0f

    companion object {
        private const val TAG = "ASREngine"
    }

    /**
     * Initialize for a language. If the model isn't copied yet, copies from assets with progress.
     * @param onStatus callback to report progress text (optional)
     */
    @Synchronized
    fun init(lang: String = "en", onStatus: ((String) -> Unit)? = null) {
        Log.d(TAG, "init() called with lang=$lang, currentLang=$currentLang")

        if (lang == currentLang && model != null && recognizer != null) {
            Log.d(TAG, "Model already initialized for $lang, skipping")
            return
        }

        close()
        currentLang = lang

        val assetsPath = "models/$lang"
        Log.d(TAG, "Checking assets at: $assetsPath")

        // Validate assets exist
        val top = try {
            context.assets.list(assetsPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list assets at $assetsPath", e)
            throw IllegalArgumentException("Cannot access assets at $assetsPath: ${e.message}", e)
        }

        Log.d(TAG, "Assets found: ${top?.joinToString(", ") ?: "null"}")

        require(!(top == null || top.isEmpty())) {
            val msg = "No ASR model assets at assets/$assetsPath. Put the model files directly under that folder."
            Log.e(TAG, msg)
            msg
        }

        val dstDir = File(context.filesDir, "models/$lang")
        Log.d(TAG, "Target directory: ${dstDir.absolutePath}")
        Log.d(TAG, "Target exists: ${dstDir.exists()}, .ready exists: ${File(dstDir, ".ready").exists()}")

        if (!File(dstDir, ".ready").exists()) {
            Log.i(TAG, "Model not ready, starting copy process...")

            // Count files for progress
            val total = countAssetFiles(assetsPath)
            Log.d(TAG, "Total files to copy: $total")

            var copied = 0
            fun updateProgress() {
                val msg = "Copying model ($lang): $copied / $total …"
                Log.d(TAG, msg)
                onStatus?.invoke(msg)
            }

            try {
                copyAssetFolder(assetsPath, dstDir) {
                    copied++
                    if (copied % 10 == 0 || copied == total) updateProgress()
                }
                File(dstDir, ".ready").writeText("ok")
                Log.i(TAG, "Model copy complete")
                onStatus?.invoke("Copy complete ($lang)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy model files", e)
                throw RuntimeException("Model copy failed: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Model already copied, loading from disk")
        }

        // List what's actually in the directory
        Log.d(TAG, "Contents of $dstDir:")
        dstDir.listFiles()?.forEach {
            Log.d(TAG, "  - ${it.name} (${if (it.isDirectory) "dir" else "file ${it.length()} bytes"})")
        }

        try {
            Log.i(TAG, "Initializing Vosk model from: ${dstDir.absolutePath}")
            model = Model(dstDir.absolutePath)
            Log.i(TAG, "Vosk model loaded successfully")

            Log.i(TAG, "Creating recognizer with sample rate: $sampleRate")
            recognizer = Recognizer(model, sampleRate)
            Log.i(TAG, "Recognizer created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Vosk model/recognizer", e)
            throw RuntimeException("Vosk initialization failed: ${e.message}", e)
        }
    }

    fun acceptPcm16(bytes: ByteArray, len: Int): String? {
        val rec = recognizer ?: return null
        try {
            val isFinal = rec.acceptWaveForm(bytes, len)
            val json = if (isFinal) rec.result else rec.partialResult
            val j = JSONObject(json)
            val text = j.optString("text", j.optString("partial", ""))
            if (text.isNotBlank()) {
                Log.v(TAG, "Recognized: $text (final=$isFinal)")
            }
            return text.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Error in acceptPcm16", e)
            return null
        }
    }

    @Synchronized
    fun close() {
        Log.d(TAG, "close() called")
        try { recognizer?.close() } catch (e: Throwable) { Log.e(TAG, "Error closing recognizer", e) }
        try { model?.close() } catch (e: Throwable) { Log.e(TAG, "Error closing model", e) }
        recognizer = null
        model = null
    }

    // ---------- assets helpers with progress ----------

    private fun countAssetFiles(assetPath: String): Int {
        val am = context.assets
        val list = try {
            am.list(assetPath)
        } catch (e: Exception) {
            Log.e(TAG, "Error counting files at $assetPath", e)
            return 0
        } ?: return 0

        if (list.isEmpty()) return 1 // it's a file
        var sum = 0
        for (name in list) {
            val p = "$assetPath/$name"
            val kids = am.list(p) ?: emptyArray()
            sum += if (kids.isEmpty()) 1 else countAssetFiles(p)
        }
        return sum
    }

    /** Copy folder; calls onFileCopied() once per file copied. */
    private fun copyAssetFolder(assetPath: String, dstDir: File, onFileCopied: (() -> Unit)?) {
        val am = context.assets
        val list = try {
            am.list(assetPath)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing $assetPath during copy", e)
            throw e
        } ?: emptyArray()

        if (list.isEmpty()) {
            // assetPath is a file, dstDir is the target *file*
            Log.v(TAG, "Copying file: $assetPath -> ${dstDir.absolutePath}")
            try {
                am.open(assetPath).use { input ->
                    FileOutputStream(dstDir).use { out -> input.copyTo(out) }
                }
                onFileCopied?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy file $assetPath", e)
                throw e
            }
            return
        }

        if (!dstDir.exists()) {
            Log.v(TAG, "Creating directory: ${dstDir.absolutePath}")
            dstDir.mkdirs()
        }

        for (name in list) {
            val childAsset = "$assetPath/$name"
            val childList = am.list(childAsset) ?: emptyArray()
            val childDst = File(dstDir, name)
            if (childList.isEmpty()) {
                try {
                    am.open(childAsset).use { input ->
                        FileOutputStream(childDst).use { out -> input.copyTo(out) }
                    }
                    onFileCopied?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy $childAsset", e)
                    throw e
                }
            } else {
                copyAssetFolder(childAsset, childDst, onFileCopied)
            }
        }
    }
}