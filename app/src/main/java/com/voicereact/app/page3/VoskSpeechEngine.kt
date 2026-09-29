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

    private fun ensureModel(context: Context, log: (String) -> Unit): Model {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            val modelRoot = File(context.filesDir, "vosk_model")
            val alreadyExtracted = modelRoot.exists() && !modelRoot.listFiles().isNullOrEmpty()

            if (!alreadyExtracted) {
                modelRoot.deleteRecursively()
                modelRoot.mkdirs()
                var entryCount = 0
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
                            entryCount++
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
                log("فك موديل Vosk: $entryCount عنصر")
            }

            val marker = findFileNamed(modelRoot, "final.mdl")
                ?: findFileNamed(modelRoot, "model.conf")
            val actualRoot = marker?.parentFile?.parentFile

            if (actualRoot == null) {
                val tree = StringBuilder()
                listTree(modelRoot, 0, tree)
                log("محتوى vosk_model.zip بعد الفك (${modelRoot.absolutePath}):\n$tree")
                throw IllegalStateException("ما لقيت final.mdl ولا model.conf داخل vosk_model.zip")
            }

            log("جذر موديل Vosk الفعلي: ${actualRoot.absolutePath}")
            val m = Model(actualRoot.absolutePath)
            model = m
            return m
        }
    }

    private fun findFileNamed(dir: File, name: String): File? {
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isFile && f.name == name) return f
        }
        for (f in files) {
            if (f.isDirectory) {
                val found = findFileNamed(f, name)
                if (found != null) return found
            }
        }
        return null
    }

    private fun listTree(dir: File, depth: Int, sb: StringBuilder) {
        if (sb.length > 3500 || depth > 5) return
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        for (f in files) {
            sb.append("  ".repeat(depth)).append(if (f.isDirectory) "[D] " else "").append(f.name).append("\n")
            if (f.isDirectory) listTree(f, depth + 1, sb)
        }
    }

    fun recognize(context: Context, samples: ShortArray, sampleRate: Int, log: (String) -> Unit): String {
        val m = ensureModel(context, log)
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
