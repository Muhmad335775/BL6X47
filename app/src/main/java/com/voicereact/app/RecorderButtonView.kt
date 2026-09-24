package com.voicereact.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

class RecorderButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onPressStart()
        fun onSwipeCancel()
        fun onRelease(committed: Boolean)
    }

    var listener: Listener? = null
    var isRecording: Boolean = false
        private set

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ripple)
        style = Paint.Style.FILL
    }
    private val micFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        style = Paint.Style.FILL
    }
    private val micStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var rippleRadiusFraction = 0f
    private var rippleAnimator: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private val cancelThresholdPx = dp(100f)
    private var cancelled = false
    private var pressActive = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val buttonRadius = minOf(width, height) / 2f

        if (rippleRadiusFraction > 0f) {
            val rippleRadius = buttonRadius + (buttonRadius * 1.6f * rippleRadiusFraction)
            canvas.drawCircle(cx, cy, rippleRadius, ripplePaint)
        }

        buttonPaint.color = ContextCompat.getColor(
            context,
            if (isRecording) R.color.mic_button_recording else R.color.mic_button
        )
        canvas.drawCircle(cx, cy, buttonRadius, buttonPaint)

        drawMicIcon(canvas, cx, cy, buttonRadius)
    }

    // WhatsApp-style mic glyph: rounded capsule head + curved stand + stem + base line
    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val scale = r / 32f // design at a 32-unit radius, then scale to actual button size
        micStrokePaint.strokeWidth = 2.6f * scale

        val headW = 11f * scale
        val headH = 17f * scale
        val headTop = cy - 15f * scale
        val headRect = RectF(cx - headW / 2f, headTop, cx + headW / 2f, headTop + headH)
        canvas.drawRoundRect(headRect, headW / 2f, headW / 2f, micFillPaint)

        val standRadius = 10f * scale
        val standRect = RectF(cx - standRadius, cy - 4f * scale, cx + standRadius, cy + standRadius * 2f - 4f * scale)
        canvas.drawArc(standRect, 15f, 150f, false, micStrokePaint)

        val stemTop = cy + standRadius - 3f * scale
        val stemBottom = cy + 16f * scale
        canvas.drawLine(cx, stemTop, cx, stemBottom, micStrokePaint)

        val baseHalf = 6f * scale
        canvas.drawLine(cx - baseHalf, stemBottom, cx + baseHalf, stemBottom, micStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                cancelled = false
                pressActive = true
                startPress()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressActive) return true
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!cancelled && dx < -cancelThresholdPx && kotlin.math.abs(dy) < cancelThresholdPx) {
                    cancelled = true
                    pressActive = false
                    listener?.onSwipeCancel()
                    endPress()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressActive && !cancelled) {
                    pressActive = false
                    endPress()
                    listener?.onRelease(true)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pressActive && !cancelled) {
                    pressActive = false
                    endPress()
                    listener?.onRelease(false)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun startPress() {
        vibrateTap()
        listener?.onPressStart()
        rippleAnimator?.cancel()
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                rippleRadiusFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Called by MainActivity once recording actually starts (after permission is confirmed). */
    fun setRecordingState(recording: Boolean) {
        isRecording = recording
        invalidate()
    }

    private fun endPress() {
        isRecording = false
        rippleAnimator?.cancel()
        rippleAnimator = null
        rippleRadiusFraction = 0f
        invalidate()
    }

    private fun vibrateTap() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(20)
                }
            }
        } catch (e: Exception) {
            // Vibration is a nice-to-have; never crash the recording flow over it
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rippleAnimator?.cancel()
        rippleAnimator = null
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
