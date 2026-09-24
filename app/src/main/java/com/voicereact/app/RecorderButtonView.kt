package com.voicereact.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
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

    // Fixed real-world button size, independent of this view's (larger) layout bounds.
    private val buttonRadiusPx get() = dp(32f)

    private var rippleRadiusFraction = 0f
    private var rippleAnimator: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private val cancelThresholdPx = dp(100f)
    private var cancelled = false
    private var pressActive = false

    private var soundLoaded = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val r = buttonRadiusPx
        // Anchor the button to this view's own bottom-right corner, so the
        // extra space (needed for the ripple) extends up-and-left only —
        // the visible button never moves from its original screen position.
        val cx = width - r
        val cy = height - r

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
        playTapSound()
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
        }
    }

    private fun playTapSound() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (!soundLoaded) {
                audioManager?.loadSoundEffects()
                soundLoaded = true
            }
            audioManager?.playSoundEffect(AudioManager.FX_KEY_CLICK)
        } catch (e: Exception) {
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rippleAnimator?.cancel()
        rippleAnimator = null
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
