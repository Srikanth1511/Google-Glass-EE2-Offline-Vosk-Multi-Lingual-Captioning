package com.srikanth.glassEE2.captions.stt

import android.content.Context
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

    /**
     * Initialize for a language. If the model isn't copied yet, copies from assets with progress.
     * @param onStatus callback to report progress text (optional)
     */
    @Synchronized
    fun init(lang: String = "en", onStatus: ((String) -> Unit)? = null) {
        if (lang == currentLang && model != null && recognizer != null) return
        close()
        currentLang = lang

        val assetsPath = "models/$lang"
        // Validate assets exist
        val top = context.assets.list(assetsPath)
        require(!(top == null || top.isEmpty())) {
            "No ASR model assets at assets/$assetsPath. Put the model files directly under that folder."
        }

        val dstDir = File(context.filesDir, "models/$lang")
        if (!File(dstDir, ".ready").exists()) {
            // count files for progress
            val total = countAssetFiles(assetsPath)
            var copied = 0
            fun updateProgress() {
                onStatus?.invoke("Copying model ($lang): $copied / $total …")
            }
            copyAssetFolder(assetsPath, dstDir) {
                copied++
                if (copied % 10 == 0 || copied == total) updateProgress()
            }
            File(dstDir, ".ready").writeText("ok")
            onStatus?.invoke("Copy complete ($lang)")
        }

        model = Model(dstDir.absolutePath)
        recognizer = Recognizer(model, sampleRate)
    }

    fun acceptPcm16(bytes: ByteArray, len: Int): String? {
        val rec = recognizer ?: return null
        val isFinal = rec.acceptWaveForm(bytes, len)
        val json = if (isFinal) rec.result else rec.partialResult
        val j = JSONObject(json)
        val text = j.optString("text", j.optString("partial", ""))
        return text.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun close() {
        try { recognizer?.close() } catch (_: Throwable) {}
        try { model?.close() } catch (_: Throwable) {}
        recognizer = null
        model = null
    }

    // ---------- assets helpers with progress ----------

    private fun countAssetFiles(assetPath: String): Int {
        val am = context.assets
        val list = am.list(assetPath) ?: return 0
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
        val list = am.list(assetPath) ?: emptyArray()
        if (list.isEmpty()) {
            // assetPath is a file, dstDir is the target *file*
            am.open(assetPath).use { input ->
                FileOutputStream(dstDir).use { out -> input.copyTo(out) }
            }
            onFileCopied?.invoke()
            return
        }
        if (!dstDir.exists()) dstDir.mkdirs()
        for (name in list) {
            val childAsset = "$assetPath/$name"
            val childList = am.list(childAsset) ?: emptyArray()
            val childDst = File(dstDir, name)
            if (childList.isEmpty()) {
                am.open(childAsset).use { input ->
                    FileOutputStream(childDst).use { out -> input.copyTo(out) }
                }
                onFileCopied?.invoke()
            } else {
                copyAssetFolder(childAsset, childDst, onFileCopied)
            }
        }
    }
}
