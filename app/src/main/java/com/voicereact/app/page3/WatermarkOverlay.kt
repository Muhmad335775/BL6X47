package com.voicereact.app.page3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small animated "VoiceBL7X4" watermark that drifts between the four corners of the
 * screen as the video renders. Pure 2D overlay drawn on top of the already-rendered
 * Filament frame — it never touches the 3D scene, lighting, voice, or body logic.
 */
class WatermarkOverlay(private val context: Context, width: Int, height: Int) {

    companion object {
        private const val TEXT = "VoiceBL7X4"
        private const val CYCLE_SECONDS = 8f // full loop through all 4 corners
        private const val MARGIN_DP = 14f
        private const val TEXT_SIZE_DP = 13f
    }

    private val density = context.resources.displayMetrics.density
    private val margin = MARGIN_DP * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TEXT_SIZE_DP * density
        isFakeBoldText = true
        setShadowLayer(4f * density, 0f, 1f * density, Color.parseColor("#99000000"))
    }
    private val textWidth = paint.measureText(TEXT)
    private val textHeight = paint.fontMetrics.let { it.descent - it.ascent }

    private val screenW = width.toFloat()
    private val screenH = height.toFloat()

    // The 4 corner anchor points, inset by margin, for the watermark's top-left origin.
    private val corners = arrayOf(
        floatArrayOf(margin, margin),                                             // top-left
        floatArrayOf(screenW - textWidth - margin, margin),                       // top-right
        floatArrayOf(screenW - textWidth - margin, screenH - textHeight - margin),// bottom-right
        floatArrayOf(margin, screenH - textHeight - margin)                       // bottom-left
    )

    /** Draws the watermark onto the given canvas at time t (seconds), cycling smoothly
     *  corner-to-corner with a soft shimmer gradient and gentle fade at each transition. */
    fun draw(canvas: Canvas, t: Float) {
        val cyclePos = (t % CYCLE_SECONDS) / CYCLE_SECONDS * 4f
        val fromIndex = cyclePos.toInt() % 4
        val toIndex = (fromIndex + 1) % 4
        val localProgress = cyclePos - fromIndex.toFloat()
        val eased = easeInOut(localProgress)

        val from = corners[fromIndex]
        val to = corners[toIndex]
        val x = from[0] + (to[0] - from[0]) * eased
        val y = from[1] + (to[1] - from[1]) * eased

        val pulse = 0.5f + 0.5f * sin(t.toDouble() * 2.0 * PI / 2.2).toFloat()
        val shimmer = LinearGradient(
            x, y, x + textWidth, y,
            intArrayOf(
                Color.parseColor("#E8FFFFFF"),
                Color.parseColor("#FFFFFFFF"),
                Color.parseColor("#E8FFFFFF")
            ),
            floatArrayOf(0f, 0.5f + 0.3f * (pulse - 0.5f), 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = shimmer
        paint.alpha = 235

        val baselineY = y - paint.fontMetrics.ascent
        canvas.drawText(TEXT, x, baselineY, paint)
    }

    private fun easeInOut(p: Float): Float {
        val c = p.coerceIn(0f, 1f)
        return if (c < 0.5f) 2f * c * c else 1f - ((-2f * c + 2f).let { it * it } / 2f)
    }
}
