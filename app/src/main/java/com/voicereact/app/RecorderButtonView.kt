package com.voicereact.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
        addView(micIcon, LayoutParams(dp(26), dp(26)).apply { gravity = android.view.Gravity.CENTER })

        isClickable = true
        isFocusable = true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                cancelled = false
                pressed = true
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
