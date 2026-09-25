// New file: draws the small circular trash-can target used during swipe-to-cancel
package com.voicereact.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class TrashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // 0f = neutral/small (just appeared), 1f = fully armed to delete (finger over it)
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(width, height) / 2f
        val r = maxR * (0.55f + 0.45f * progress) // grows slightly as it arms

        val neutralColor = ContextCompat.getColor(context, R.color.cancel_hint)
        val armedColor = ContextCompat.getColor(context, R.color.mic_button_recording)
        circlePaint.color = blendColor(neutralColor, armedColor, progress)
        canvas.drawCircle(cx, cy, r, circlePaint)

        iconPaint.color = ContextCompat.getColor(context, R.color.white)
        iconPaint.strokeWidth = r * 0.12f

        val iconScale = r * 0.5f
        // Trash can lid
        canvas.drawLine(cx - iconScale, cy - iconScale * 0.55f, cx + iconScale, cy - iconScale * 0.55f, iconPaint)
        // Trash can body
        val body = RectF(cx - iconScale * 0.75f, cy - iconScale * 0.4f, cx + iconScale * 0.75f, cy + iconScale)
        canvas.drawRoundRect(body, iconScale * 0.15f, iconScale * 0.15f, iconPaint)
        // Handle
        canvas.drawLine(cx - iconScale * 0.3f, cy - iconScale * 0.55f, cx - iconScale * 0.3f, cy - iconScale * 0.85f, iconPaint)
        canvas.drawLine(cx + iconScale * 0.3f, cy - iconScale * 0.55f, cx + iconScale * 0.3f, cy - iconScale * 0.85f, iconPaint)
        canvas.drawLine(cx - iconScale * 0.3f, cy - iconScale * 0.85f, cx + iconScale * 0.3f, cy - iconScale * 0.85f, iconPaint)
    }

    private fun blendColor(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = ((1 - f) * ((from shr 24) and 0xFF) + f * ((to shr 24) and 0xFF)).toInt()
        val r = ((1 - f) * ((from shr 16) and 0xFF) + f * ((to shr 16) and 0xFF)).toInt()
        val g = ((1 - f) * ((from shr 8) and 0xFF) + f * ((to shr 8) and 0xFF)).toInt()
        val b = ((1 - f) * (from and 0xFF) + f * (to and 0xFF)).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
