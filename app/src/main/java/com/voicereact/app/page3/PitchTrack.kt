package com.voicereact.app.page3

import kotlin.math.min
import kotlin.math.sqrt

object PitchTrack {

    fun build(samples: ShortArray, sampleRate: Int, fps: Int, frames: Int): FloatArray {
        val out = FloatArray(frames)
        val span = (sampleRate * 0.05).toInt().coerceAtLeast(64)
        var smooth = 150f
        for (f in 0 until frames) {
            val center = ((f.toDouble() / fps) * sampleRate).toInt()
            val start = center - span / 2
            var pitch = 0f
            if (start >= 0 && start + span <= samples.size) {
                pitch = estimate(samples, start, span, sampleRate)
            }
            if (pitch in 60f..500f) {
                smooth += (pitch - smooth) * 0.2f
            }
            out[f] = smooth
        }
        return out
    }

    private fun estimate(samples: ShortArray, start: Int, span: Int, sampleRate: Int): Float {
        val step = 4
        val rate = sampleRate / step
        val n = span / step
        val minLag = rate / 500
        val maxLag = min(rate / 60, n - 2)
        if (minLag < 1 || maxLag <= minLag) return 0f

        var energy = 0.0
        for (i in 0 until n) {
            val v = samples[start + i * step].toDouble()
            energy += v * v
        }
        if (sqrt(energy / n) < 250.0) return 0f

        var bestLag = -1
        var best = 0.0
        for (lag in minLag..maxLag) {
            var c = 0.0
            val limit = n - lag
            var i = 0
            while (i < limit) {
                c += samples[start + i * step].toDouble() * samples[start + (i + lag) * step].toDouble()
                i++
            }
            if (c > best) {
                best = c
                bestLag = lag
            }
        }
        return if (bestLag > 0) rate.toFloat() / bestLag.toFloat() else 0f
    }
}
