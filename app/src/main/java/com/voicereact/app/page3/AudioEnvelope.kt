package com.voicereact.app.page3

object AudioEnvelope {
    fun build(samples: ShortArray, sampleRate: Int, windowSeconds: Float = 0.03f): FloatArray {
        val windowSize = (sampleRate * windowSeconds).toInt().coerceAtLeast(1)
        val windowCount = (samples.size / windowSize).coerceAtLeast(1)
        val envelope = FloatArray(windowCount)
        for (w in 0 until windowCount) {
            var sumSquares = 0.0
            val start = w * windowSize
            val end = (start + windowSize).coerceAtMost(samples.size)
            for (i in start until end) {
                val v = samples[i] / 32768.0
                sumSquares += v * v
            }
            val count = (end - start).coerceAtLeast(1)
            val rms = kotlin.math.sqrt(sumSquares / count)
            envelope[w] = rms.toFloat()
        }
        val maxVal = envelope.maxOrNull()?.takeIf { it > 0.0001f } ?: 1f
        for (i in envelope.indices) envelope[i] = (envelope[i] / maxVal).coerceIn(0f, 1f)
        return envelope
    }

    fun sample(envelope: FloatArray, timeSeconds: Float, windowSeconds: Float = 0.03f): Float {
        if (envelope.isEmpty()) return 0f
        val idx = (timeSeconds / windowSeconds).toInt().coerceIn(0, envelope.size - 1)
        return envelope[idx]
    }
}
