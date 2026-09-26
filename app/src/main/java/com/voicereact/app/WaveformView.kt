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
    // Rest amplitude raised (was 0.05f) so bars read as a real quiet
    // waveform, not a flat dashed line, even before any sound is picked up.
    private val restAmplitude = 0.18f
    private val amplitudes = MutableList(maxBars) { restAmplitude }

    private val barPaintActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.waveform_bar)
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val barPaintDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.waveform_bar_dim)
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }

    fun pushAmplitude(rawAmplitude: Int) {
        val normalized = (rawAmplitude / 32767f).coerceIn(restAmplitude, 1f)
        amplitudes.removeAt(0)
        amplitudes.add(normalized)
        invalidate()
    }

    fun reset() {
        for (i in amplitudes.indices) amplitudes[i] = restAmplitude
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val gap = width / maxBars.toFloat()
        val midY = height / 2f
        for (i in amplitudes.indices) {
            val x = i * gap + gap / 2f
            val amp = amplitudes[i]
            val barHeight = amp * height * 0.9f
            // Bars that are essentially at rest draw dimmer, active bars
            // draw at full color — makes real speech visually pop.
            val paint = if (amp > restAmplitude + 0.05f) barPaintActive else barPaintDim
            canvas.drawLine(x, midY - barHeight / 2f, x, midY + barHeight / 2f, paint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        fun simulatedTick(): Int = Random.nextInt(4000, 26000)
    }
}
