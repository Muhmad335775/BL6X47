package com.voicereact.app.page3

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.cos
import kotlin.math.sqrt

class OpenVoiceToneConverter(private val context: Context) {

    companion object {
        const val TARGET_SAMPLE_RATE = 22050
        private const val N_FFT = 1024
        private const val HOP = 256
        private const val WIN = 1024
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var extractSession: OrtSession? = null
    private var converterSession: OrtSession? = null

    fun loadModels(log: (String) -> Unit) {
        val extractFile = copySingleAsset("openvoice/tone_extract.onnx", "tone_extract.onnx")
        val converterFile = assembleSplitAsset("openvoice", "tone_color_converter.onnx", log)
        extractSession = env.createSession(extractFile.absolutePath, OrtSession.SessionOptions())
        converterSession = env.createSession(converterFile.absolutePath, OrtSession.SessionOptions())
        log("نماذج OpenVoice تحمّلت: ${extractFile.length() / 1024}KB + ${converterFile.length() / 1024 / 1024}MB")
    }

    private fun copySingleAsset(assetPath: String, fileName: String): File {
        val outFile = File(context.filesDir, fileName)
        if (!outFile.exists() || outFile.length() < 1000L) {
            context.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return outFile
    }

    /** يدمج أجزاء ملف مقسّم (tone_color_converter.onnx.part1, part2...) بأي تسمية، بالترتيب الرقمي الصحيح */
    private fun assembleSplitAsset(folder: String, baseName: String, log: (String) -> Unit): File {
        val outFile = File(context.filesDir, baseName)
        val allNames = context.assets.list(folder)?.toList() ?: emptyList()

        if (allNames.contains(baseName)) {
            log("$baseName موجود كملف واحد كامل")
            return copySingleAsset("$folder/$baseName", baseName)
        }

        val parts = allNames
            .filter { it.startsWith(baseName) && it != baseName }
            .sortedWith(compareBy { extractTrailingNumber(it) })

        if (parts.isEmpty()) {
            throw IllegalStateException("ما لقيت $baseName ولا أي جزء منه داخل assets/$folder")
        }

        val expectedSize = parts.sumOf { context.assets.openFd("$folder/$it").length }
        if (outFile.exists() && outFile.length() == expectedSize) {
            log("$baseName مجمّع أصلاً (${outFile.length() / 1024 / 1024}MB) — تخطي الدمج")
            return outFile
        }

        log("دمج ${parts.size} جزء: ${parts.joinToString(", ")}")
        outFile.outputStream().use { output ->
            for (part in parts) {
                context.assets.open("$folder/$part").use { input -> input.copyTo(output) }
            }
        }
        log("الحجم بعد الدمج: ${outFile.length() / 1024 / 1024}MB (المتوقع: ${expectedSize / 1024 / 1024}MB)")
        if (outFile.length() != expectedSize) {
            throw IllegalStateException("حجم الملف المدموج ما يطابق المتوقع — الدمج فشل")
        }
        return outFile
    }

    private fun extractTrailingNumber(name: String): Int {
        val digits = name.takeLastWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }

    fun convert(
        userSamples: FloatArray,
        userSampleRate: Int,
        characterNumber: Int,
        log: (String) -> Unit
    ): FloatArray {
        val extractor = extractSession ?: throw IllegalStateException("ما تحمّلت نماذج OpenVoice")
        val converter = converterSession ?: throw IllegalStateException("ما تحمّلت نماذج OpenVoice")

        val userResampled = resample(userSamples, userSampleRate, TARGET_SAMPLE_RATE)
        val srcEmbedding = extractEmbedding(extractor, userResampled)

        val targetFile = "${characterNumber}m/target_voice.wav"
        val targetPcm = try {
            context.assets.open(targetFile).use { WavPcmReader.readStream(it) }
        } catch (e: Exception) {
            log("تحذير: ما لقيت ${targetFile} — رح يرجع صوتك الأصلي لهالشخصية")
            return userSamples
        }
        val targetFloats = shortsToFloats(targetPcm.samples)
        val targetResampled = resample(targetFloats, targetPcm.sampleRate, TARGET_SAMPLE_RATE)
        val tgtEmbedding = extractEmbedding(extractor, targetResampled)

        val spec = linearSpectrogram(userResampled)
        val tMel = spec[0].size

        val specFlat = FloatArray(513 * tMel)
        for (f in 0 until 513) {
            for (t in 0 until tMel) {
                specFlat[f * tMel + t] = spec[f][t]
            }
        }

        val specTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(specFlat), longArrayOf(1, 513, tMel.toLong()))
        val lenTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(tMel.toLong())), longArrayOf(1))
        val srcTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(srcEmbedding), longArrayOf(1, 256, 1))
        val tgtTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(tgtEmbedding), longArrayOf(1, 256, 1))

        val inputs = mapOf(
            "y" to specTensor,
            "y_lengths" to lenTensor,
            "sid_src" to srcTensor,
            "sid_tgt" to tgtTensor
        )

        converter.run(inputs).use { result ->
            val audioOut = result[0].value as Array<Array<FloatArray>>
            specTensor.close(); lenTensor.close(); srcTensor.close(); tgtTensor.close()
            val outFloats = audioOut[0][0]
            log("تحويل الطبقة: طول الدخل=${userResampled.size} طول الخرج=${outFloats.size}")
            return resample(outFloats, TARGET_SAMPLE_RATE, userSampleRate)
        }
    }

    private fun extractEmbedding(session: OrtSession, samples: FloatArray): FloatArray {
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong()))
        session.run(mapOf("audio" to inputTensor)).use { result ->
            inputTensor.close()
            val out = result[0].value as Array<Array<FloatArray>>
            return out[0].map { it[0] }.toFloatArray()
        }
    }

    private fun linearSpectrogram(samples: FloatArray): Array<FloatArray> {
        val padded = FloatArray(samples.size + N_FFT)
        System.arraycopy(samples, 0, padded, N_FFT / 2, samples.size)
        val frames = (padded.size - N_FFT) / HOP + 1
        val spec = Array(N_FFT / 2 + 1) { FloatArray(frames) }
        val window = FloatArray(WIN) { i -> (0.5 - 0.5 * cos(2.0 * Math.PI * i / (WIN - 1))).toFloat() }

        for (t in 0 until frames) {
            val re = FloatArray(N_FFT)
            val im = FloatArray(N_FFT)
            val start = t * HOP
            for (i in 0 until N_FFT) {
                val sample = if (start + i < padded.size) padded[start + i] else 0f
                re[i] = sample * (if (i < WIN) window[i] else 0f)
            }
            FftTransform.forward(re, im)
            for (f in 0..N_FFT / 2) {
                spec[f][t] = sqrt(re[f] * re[f] + im[f] * im[f] + 1e-6f)
            }
        }
        return spec
    }

    private fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outLen = (samples.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in out.indices) {
            val srcPos = i / ratio
            val idx0 = srcPos.toInt().coerceIn(0, samples.size - 1)
            val idx1 = (idx0 + 1).coerceAtMost(samples.size - 1)
            val frac = (srcPos - idx0).toFloat()
            out[i] = samples[idx0] * (1 - frac) + samples[idx1] * frac
        }
        return out
    }

    private fun shortsToFloats(shorts: ShortArray): FloatArray =
        FloatArray(shorts.size) { shorts[it] / 32768f }

    fun floatsToShorts(floats: FloatArray): ShortArray =
        ShortArray(floats.size) { (floats[it] * 32768f).coerceIn(-32768f, 32767f).toInt().toShort() }

    fun release() {
        try { extractSession?.close() } catch (ignored: Throwable) { }
        try { converterSession?.close() } catch (ignored: Throwable) { }
    }
}
