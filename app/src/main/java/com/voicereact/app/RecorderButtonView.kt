package com.voicereact.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.hypot

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

    init {
        isClickable = false
        isFocusable = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        background = null
    }

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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

    private val buttonRadiusPx get() = dp(32f)
    // Small extra margin around the visible circle so a finger slightly at
    // the edge still grabs it — same forgiving feel as WhatsApp's button.
    private val grabRadiusPx get() = buttonRadiusPx + dp(6f)

    private var rippleRadiusFraction = 0f
    private var rippleAnimator: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private val cancelThresholdPx = dp(100f)
    private var cancelled = false
    private var pressActive = false

    private var toneGenerator: ToneGenerator? = null

    private fun buttonCenterX() = width - buttonRadiusPx
    private fun buttonCenterY() = height - buttonRadiusPx

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val r = buttonRadiusPx
        val cx = buttonCenterX()
        val cy = buttonCenterY()

        if (rippleRadiusFraction > 0f) {
            val rippleRadius = r + (r * 0.6f * rippleRadiusFraction)
            canvas.drawCircle(cx, cy, rippleRadius, ripplePaint)
        }

        buttonPaint.color = ContextCompat.getColor(
            context,
            if (isRecording) R.color.mic_button_recording else R.color.mic_button
        )
        canvas.drawCircle(cx, cy, r, buttonPaint)

        drawMicIcon(canvas, cx, cy, r)
    }

    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val scale = (r * 1.15f) / 24f
        fun mx(px: Float) = cx + (px - 12f) * scale
        fun my(py: Float) = cy + (py - 12f) * scale

        micStrokePaint.strokeWidth = 1.8f * scale

        val head = RectF(mx(9f), my(3f), mx(15f), my(15f))
        canvas.drawRoundRect(head, (head.width() / 2f), (head.width() / 2f), micFillPaint)

        val bracket = RectF(mx(6f), my(9f), mx(18f), my(19f))
        canvas.drawArc(bracket, 0f, 180f, false, micStrokePaint)

        canvas.drawLine(mx(12f), my(19f), mx(12f), my(22f), micStrokePaint)
        canvas.drawLine(mx(8f), my(22f), mx(16f), my(22f), micStrokePaint)
    }

    // Only the real button circle (plus a small grab margin) responds to touch —
    // the rest of this view's extra drawing space is purely visual, never touchable.
    private fun isInsideButton(x: Float, y: Float): Boolean {
        val dist = hypot((x - buttonCenterX()).toDouble(), (y - buttonCenterY()).toDouble())
        return dist <= grabRadiusPx
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isInsideButton(event.x, event.y)) {
                    return false
                }
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
        playTapTone()
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
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(25)
                }
            }
        } catch (e: Exception) {
        }
    }

    // Softer, shorter "tap" instead of a loud telephone-style beep — closer to a UI click.
    private fun playTapTone() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 40)
        } catch (e: Exception) {
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rippleAnimator?.cancel()
        rippleAnimator = null
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
