package com.voicereact.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.random.Random

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxBars = 40
    private val amplitudes = MutableList(maxBars) { 0.05f }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.waveform_bar)
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }

    fun pushAmplitude(rawAmplitude: Int) {
        val normalized = (rawAmplitude / 32767f).coerceIn(0.05f, 1f)
        amplitudes.removeAt(0)
        amplitudes.add(normalized)
        invalidate()
    }

    fun reset() {
        for (i in amplitudes.indices) amplitudes[i] = 0.05f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val gap = width / maxBars.toFloat()
        val midY = height / 2f
        for (i in amplitudes.indices) {
            val x = i * gap + gap / 2f
            val barHeight = amplitudes[i] * height * 0.9f
            canvas.drawLine(x, midY - barHeight / 2f, x, midY + barHeight / 2f, barPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        fun simulatedTick(): Int = Random.nextInt(4000, 26000)
    }
}
