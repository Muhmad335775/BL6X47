package com.voicereact.app.page3

import java.io.File
import kotlin.math.PI
import kotlin.math.sin

object EchoSignatureGenerator {

    fun generate(originalAudioPcm: ShortArray, sampleRate: Int, outputWav: File): File {
        val tailSeconds = 1.5f
        val tailSamples = (sampleRate * tailSeconds).toInt().coerceAtMost(originalAudioPcm.size)
        val tail = originalAudioPcm.copyOfRange(originalAudioPcm.size - tailSamples, originalAudioPcm.size)

        val fundamentalHz = estimatePitchAutocorrelation(tail, sampleRate)
        val toneSamples = synthesizeSignatureTone(fundamentalHz, sampleRate, durationSeconds = 1.2f)
        writeWav(toneSamples, sampleRate, outputWav)
        return outputWav
    }

    private fun estimatePitchAutocorrelation(samples: ShortArray, sampleRate: Int): Float {
        val minHz = 80f
        val maxHz = 500f
        val minLag = (sampleRate / maxHz).toInt()
        val maxLag = (sampleRate / minHz).toInt().coerceAtMost(samples.size - 1)

        var bestLag = minLag
        var bestScore = Float.MIN_VALUE
        for (lag in minLag..maxLag) {
            var sum = 0f
            var i = 0
            while (i + lag < samples.size) {
                sum += samples[i] * samples[i + lag]
                i++
            }
            if (sum > bestScore) {
                bestScore = sum
                bestLag = lag
            }
        }
        return if (bestLag == 0) 220f else sampleRate.toFloat() / bestLag
    }

    private fun synthesizeSignatureTone(fundamentalHz: Float, sampleRate: Int, durationSeconds: Float): ShortArray {
        val total = (sampleRate * durationSeconds).toInt()
        val out = ShortArray(total)
        val f0 = fundamentalHz.coerceIn(100f, 440f)
        for (n in 0 until total) {
            val t = n / sampleRate.toFloat()
            val fadeIn = (t / 0.08f).coerceAtMost(1f)
            val fadeOut = ((durationSeconds - t) / 0.3f).coerceIn(0f, 1f)
            val envelope = minOf(fadeIn, fadeOut)

            val wave = (
                sin(2 * PI * f0 * t) * 0.6 +
                sin(2 * PI * f0 * 2 * t) * 0.25 +
                sin(2 * PI * f0 * 3 * t) * 0.1
            ).toFloat()

            out[n] = (wave * envelope * Short.MAX_VALUE * 0.5f).toInt().toShort()
        }
        return out
    }

    private fun writeWav(pcm: ShortArray, sampleRate: Int, file: File) {
        val byteRate = sampleRate * 2
        val dataSize = pcm.size * 2
        file.outputStream().use { os ->
            fun writeInt(v: Int) { os.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())) }
            fun writeShort(v: Int) { os.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())) }

            os.write("RIFF".toByteArray()); writeInt(36 + dataSize); os.write("WAVE".toByteArray())
            os.write("fmt ".toByteArray()); writeInt(16); writeShort(1); writeShort(1)
            writeInt(sampleRate); writeInt(byteRate); writeShort(2); writeShort(16)
            os.write("data".toByteArray()); writeInt(dataSize)
            val bytes = ByteArray(dataSize)
            for (i in pcm.indices) {
                bytes[i * 2] = (pcm[i].toInt() and 0xff).toByte()
                bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xff).toByte()
            }
            os.write(bytes)
        }
    }
}
