package com.voicereact.app.page3

import java.io.File
import kotlin.math.cos
import kotlin.math.PI

object VoicePitchShifter {

    /** يرجّع صوت بنفس المدة تماماً، بس بطبقة نبرة مختلفة — تقنية Granular Overlap-Add خالص، بدون أي موديل خارجي */
    fun shift(input: ShortArray, pitchFactor: Float): ShortArray {
        if (input.isEmpty()) return input
        val grainSize = 1764 // ~40ms عند 44100Hz
        val hop = grainSize / 4
        val output = FloatArray(input.size)
        val gain = FloatArray(input.size)
        val window = FloatArray(grainSize) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (grainSize - 1))).toFloat()
        }

        var inPos = 0
        while (inPos < input.size) {
            for (i in 0 until grainSize) {
                val srcPos = inPos + i * pitchFactor
                val idx0 = srcPos.toInt()
                if (idx0 >= input.size - 1) continue
                val frac = srcPos - idx0
                val s0 = input[idx0].toFloat()
                val s1 = input[idx0 + 1].toFloat()
                val sample = (s0 + (s1 - s0) * frac) * window[i]
                val outIdx = inPos + i
                if (outIdx < output.size) {
                    output[outIdx] += sample
                    gain[outIdx] += window[i]
                }
            }
            inPos += hop
        }

        val result = ShortArray(input.size)
        for (i in input.indices) {
            val g = if (gain[i] > 0.001f) gain[i] else 1f
            val v = (output[i] / g).coerceIn(-32768f, 32767f)
            result[i] = v.toInt().toShort()
        }
        return result
    }

    /** طبقة صوت مختلفة قليلاً لكل رقم شخصية (1..15) — تنويع بسيط بين الشخصيات */
    fun pitchFactorFor(characterNumber: Int): Float {
        val variants = floatArrayOf(1.22f, 1.30f, 1.15f, 0.85f, 1.35f, 0.90f, 1.18f, 1.27f)
        return variants[characterNumber % variants.size]
    }

    fun writeWav(pcm: ShortArray, sampleRate: Int, file: File) {
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
