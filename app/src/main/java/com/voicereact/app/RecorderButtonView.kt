package com.voicereact.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.widget.FrameLayout
import android.widget.ImageView

class RecorderButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onPressStart()
        fun onDragProgress(progress: Float)
        fun onSwipeCancel()
        fun onRelease(committed: Boolean)
    }

    var listener: Listener? = null

    companion object {
        private const val CANCEL_SWIPE_PX = 260f
        // Compact WhatsApp-sized button — enforced here regardless of whatever
        // width/height the XML layout assigns this view.
        private const val BUTTON_SIZE_DP = 56
        private const val ICON_SIZE_DP = 24
    }

    private val micIcon: ImageView
    private var startX = 0f
    private var pressed = false
    private var cancelled = false

    init {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#25D366"))
        background = bg

        micIcon = ImageView(context)
        micIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
        micIcon.setColorFilter(Color.WHITE)
        addView(micIcon, LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP)).apply { gravity = Gravity.CENTER })

        isClickable = true
        isFocusable = true
        isSoundEffectsEnabled = true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Forces a fixed compact circular size no matter what the parent/XML requests,
     *  so the button can never render oversized again. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(BUTTON_SIZE_DP)
        val spec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
        setMeasuredDimension(size, size)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                cancelled = false
                pressed = true
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                playSoundEffect(SoundEffectConstants.CLICK)
                animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start()
                listener?.onPressStart()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressed) return true
                val dx = startX - event.rawX
                val progress = (dx / CANCEL_SWIPE_PX).coerceIn(0f, 1f)
                listener?.onDragProgress(progress)
                if (progress >= 1f && !cancelled) {
                    cancelled = true
                    listener?.onSwipeCancel()
                    pressed = false
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!pressed) return true
                pressed = false
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                listener?.onRelease(true)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!pressed) return true
                pressed = false
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                listener?.onRelease(false)
                return true
            }
        }
        return false
    }

    fun setRecordingState(recording: Boolean) {
        val bg = background as? GradientDrawable
        bg?.setColor(if (recording) Color.parseColor("#E53935") else Color.parseColor("#25D366"))
    }
}
