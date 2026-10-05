package com.voicereact.app.page3

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.FileProvider
import java.io.File

/**
 * TikTok-style share button: a circular arrow icon, fixed near the bottom of the screen
 * with the same vertical offset TikTok uses for its own share icon. Tapping opens Android's
 * native share sheet, which automatically lists every installed app capable of handling a
 * video (TikTok, Instagram, Snapchat, Facebook, WhatsApp, YouTube, etc.) — nothing here is
 * limited to one platform.
 */
class ShareButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val BUTTON_SIZE_DP = 56
        private const val ICON_SIZE_DP = 26
        // Same bottom offset TikTok uses for its own action icons above the nav bar.
        private const val BOTTOM_MARGIN_DP = 90
    }

    private val arrowIcon: ImageView
    private var videoFile: File? = null

    init {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#CC1A1A1A"))
        background = bg

        arrowIcon = ImageView(context)
        arrowIcon.setImageResource(android.R.drawable.ic_menu_share)
        arrowIcon.setColorFilter(Color.WHITE)
        addView(arrowIcon, LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP)).apply { gravity = Gravity.CENTER })

        isClickable = true
        isFocusable = true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(BUTTON_SIZE_DP)
        val spec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
        setMeasuredDimension(size, size)
    }

    fun setVideoFile(file: File) {
        videoFile = file
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        shareVideo()
        return true
    }

    private fun shareVideo() {
        val file = videoFile ?: return
        if (!file.exists()) return

        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (ignored: Throwable) {
            return
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(sendIntent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(chooser)
        } catch (ignored: Throwable) {
        }
    }

    fun bottomMarginPx(): Int = dp(BOTTOM_MARGIN_DP)
}
