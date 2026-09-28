package com.voicereact.app.page3

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

object VoskSpeechEngine {

    @Volatile private var model: Model? = null

    private fun ensureModel(context: Context): Model {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            val modelRoot = File(context.filesDir, "vosk_model")
            if (!modelRoot.exists() || modelRoot.listFiles().isNullOrEmpty()) {
                modelRoot.mkdirs()
                context.assets.open("vosk_model.zip").use { input ->
                    ZipInputStream(input).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val outFile = File(modelRoot, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { zip.copyTo(it) }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            }
            val actualRoot = findModelRoot(modelRoot)
                ?: throw IllegalStateException("vosk_model.zip موجود بس شكله غلط — ما لقيت مجلد am/conf بداخله")
            val m = Model(actualRoot.absolutePath)
            model = m
            return m
        }
    }

    /** بعض نماذج Vosk تنفك بمجلد فرعي واحد بدل ما تكون بجذر الملف مباشرة — هذا يدوّر عليه تلقائياً */
    private fun findModelRoot(dir: File): File? {
        if (File(dir, "am").isDirectory && File(dir, "conf").isDirectory) return dir
        val children = dir.listFiles()?.filter { it.isDirectory } ?: return null
        for (child in children) {
            val found = findModelRoot(child)
            if (found != null) return found
        }
        return null
    }

    fun recognize(context: Context, samples: ShortArray, sampleRate: Int): String {
        val m = ensureModel(context)
        val recognizer = Recognizer(m, sampleRate.toFloat())
        try {
            val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) buffer.putShort(s)
            val bytes = buffer.array()
            recognizer.acceptWaveForm(bytes, bytes.size)
            val json = JSONObject(recognizer.finalResult)
            return json.optString("text", "").trim()
        } finally {
            recognizer.close()
        }
    }
}
