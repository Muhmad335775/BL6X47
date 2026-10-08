package com.voicereact.app.page3

import kotlin.math.abs
import kotlin.math.max

/**
 * Normalizes converted voice audio to a clear, consistent loudness before muxing —
 * peak-normalizes to near full scale, then applies a soft limiter on any remaining
 * transient peaks so the result is loud and clear without clipping or distortion.
 */
object AudioLoudnessNormalizer {

    private const val TARGET_PEAK = 0.92f
    private const val LIMITER_THRESHOLD = 0.85f

    fun normalize(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples

        var peak = 0
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        if (peak < 50) return samples // near-silence: don't amplify noise floor

        val gain = (TARGET_PEAK * 32767f) / peak.toFloat()
        val gained = FloatArray(samples.size) { samples[it] * gain }

        // Soft-knee limiter: anything above the threshold gets compressed smoothly
        // instead of hard-clipped, avoiding the harsh/garbled artifacts clipping causes.
        val thresholdAbs = LIMITER_THRESHOLD * 32767f
        val out = ShortArray(samples.size)
        for (i in gained.indices) {
            val v = gained[i]
            val sign = if (v < 0) -1f else 1f
            val mag = abs(v)
            val limited = if (mag <= thresholdAbs) {
                mag
            } else {
                val over = mag - thresholdAbs
                val knee = 32767f - thresholdAbs
                thresholdAbs + knee * (1f - 1f / (1f + over / max(knee, 1f)))
            }
            out[i] = (sign * limited).coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return out
    }
}
