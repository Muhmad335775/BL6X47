package com.voicereact.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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
        color = ContextCompat.getColor(context, R.color.mic_button)
        style = Paint.Style.FILL
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ripple)
        style = Paint.Style.FILL
    }
    private val micIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        style = Paint.Style.FILL
    }

    private var rippleRadiusFraction = 0f
    private var rippleAnimator: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private val cancelThresholdPx = dp(100f)
    private var cancelled = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
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

    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val capsuleW = r * 0.55f
        val capsuleH = r * 0.9f
        canvas.drawRoundRect(
            cx - capsuleW / 2f, cy - capsuleH / 2f,
            cx + capsuleW / 2f, cy + capsuleH / 2f - dp(4f),
            capsuleW / 2f, capsuleW / 2f, micIconPaint
        )
        val standTop = cy + capsuleH / 2f - dp(2f)
        val standStrokeW = dp(2.5f)
        val stand = Paint(micIconPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = standStrokeW
        }
        canvas.drawArc(
            cx - capsuleW * 0.85f, standTop - capsuleW * 0.4f,
            cx + capsuleW * 0.85f, standTop + capsuleW * 0.9f,
            20f, 140f, false, stand
        )
        canvas.drawLine(cx, standTop + capsuleW * 0.7f, cx, standTop + capsuleW * 1.3f, stand)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                cancelled = false
                startPress()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!cancelled && dx < -cancelThresholdPx && kotlin.math.abs(dy) < cancelThresholdPx) {
                    cancelled = true
                    listener?.onSwipeCancel()
                    endPress(committed = false)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!cancelled) {
                    endPress(committed = true)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun startPress() {
        isRecording = true
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

    private fun endPress(committed: Boolean) {
        isRecording = false
        rippleAnimator?.cancel()
        rippleRadiusFraction = 0f
        invalidate()
        listener?.onRelease(committed)
    }

    private fun vibrateTap() {
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
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
