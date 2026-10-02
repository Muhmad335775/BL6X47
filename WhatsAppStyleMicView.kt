package com.voicereact.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlin.math.max
import kotlin.math.min

class WhatsAppStyleMicView(context: Context) : FrameLayout(context) {

    companion object {
        private const val MAX_RECORD_MS = 20_000L
        private const val CANCEL_SWIPE_PX = 140
    }

    interface Listener {
        fun onRecordingFinished(file: File, durationMs: Int)
    }

    var listener: Listener? = null

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recording = false
    private var startTimeMs = 0L
    private var startX = 0f
    private var cancelled = false

    private val micButton: FrameLayout
    private val rippleView: View
    private val rippleDrawable: GradientDrawable
    private val waveformRow: LinearLayout
    private val timerLabel: TextView
    private val trashIcon: ImageView
    private val hintLabel: TextView

    private val waveformBars = mutableListOf<View>()
    private var waveformUpdater: Runnable? = null
    private var timerUpdater: Runnable? = null

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(120))

        trashIcon = ImageView(context)
        trashIcon.setImageResource(android.R.drawable.ic_menu_delete)
        trashIcon.visibility = View.GONE
        addView(trashIcon, LayoutParams(dp(28), dp(28)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(20)
            marginEnd = dp(84)
        })

        hintLabel = TextView(context)
        hintLabel.text = "< Slide to cancel"
        hintLabel.setTextColor(Color.GRAY)
        hintLabel.textSize = 13f
        hintLabel.visibility = View.GONE
        addView(hintLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(32)
            marginEnd = dp(118)
        })

        timerLabel = TextView(context)
        timerLabel.text = "0:00"
        timerLabel.setTextColor(Color.DKGRAY)
        timerLabel.textSize = 14f
        timerLabel.visibility = View.GONE
        addView(timerLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = dp(32)
            marginStart = dp(16)
        })

        waveformRow = LinearLayout(context)
        waveformRow.orientation = LinearLayout.HORIZONTAL
        waveformRow.gravity = Gravity.CENTER_VERTICAL
        waveformRow.visibility = View.GONE
        addView(waveformRow, LayoutParams(0, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = dp(20)
            marginStart = dp(70)
            marginEnd = dp(84)
            width = 0
        })
        repeat(28) {
            val bar = View(context)
            val d = GradientDrawable()
            d.setColor(Color.parseColor("#25D366"))
            d.cornerRadius = dp(2).toFloat()
            bar.background = d
            val lp = LinearLayout.LayoutParams(dp(3), dp(6))
            lp.marginEnd = dp(3)
            waveformRow.addView(bar, lp)
            waveformBars.add(bar)
        }

        rippleDrawable = GradientDrawable()
        rippleDrawable.shape = GradientDrawable.OVAL
        rippleDrawable.setColor(Color.parseColor("#3325D366"))
        rippleView = View(context)
        rippleView.background = rippleDrawable
        rippleView.scaleX = 0f
        rippleView.scaleY = 0f
        addView(rippleView, LayoutParams(dp(64), dp(64)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(8)
            marginEnd = dp(4)
        })

        micButton = FrameLayout(context)
        val micBg = GradientDrawable()
        micBg.shape = GradientDrawable.OVAL
        micBg.setColor(Color.parseColor("#25D366"))
        micButton.background = micBg
        val micIcon = ImageView(context)
        micIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
        micIcon.setColorFilter(Color.WHITE)
        micButton.addView(micIcon, LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER })
        addView(micButton, LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(12)
            marginEnd = dp(8)
        })

        micButton.setOnTouchListener { _, event -> handleTouch(event) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                cancelled = false
                micButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                startRecording()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!recording) return true
                val dx = startX - event.rawX
                if (dx > CANCEL_SWIPE_PX && !cancelled) {
                    cancelled = true
                    trashIcon.setColorFilter(Color.RED)
                    hintLabel.setTextColor(Color.RED)
                } else if (dx <= CANCEL_SWIPE_PX && cancelled) {
                    cancelled = false
                    trashIcon.clearColorFilter()
                    hintLabel.setTextColor(Color.GRAY)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopRecording(cancelled)
                return true
            }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        if (recording) return
        val file = File(VoiceClipCache.directory(context), "clip_${System.currentTimeMillis()}.m4a")
        outputFile = file
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recording = true
            startTimeMs = System.currentTimeMillis()
            showRecordingUi()
            startTimerLoop()
            startWaveformLoop()
        } catch (e: Throwable) {
            recording = false
            recorder = null
        }
    }

    private fun stopRecording(wasCancelled: Boolean) {
        if (!recording) return
        recording = false
        handler.removeCallbacks(timerUpdater ?: Runnable {})
        handler.removeCallbacks(waveformUpdater ?: Runnable {})
        val durationMs = (System.currentTimeMillis() - startTimeMs).toInt()
        val r = recorder
        recorder = null
        try {
            r?.stop()
        } catch (ignored: Throwable) {
        }
        try { r?.release() } catch (ignored: Throwable) { }

        hideRecordingUi()

        val file = outputFile
        if (wasCancelled || file == null) {
            file?.delete()
            return
        }
        if (durationMs < 400 || !file.exists() || file.length() < 500L) {
            file.delete()
            return
        }
        listener?.onRecordingFinished(file, durationMs)
    }

    private fun showRecordingUi() {
        waveformRow.visibility = View.VISIBLE
        timerLabel.visibility = View.VISIBLE
        trashIcon.visibility = View.VISIBLE
        hintLabel.visibility = View.VISIBLE
        trashIcon.clearColorFilter()
        hintLabel.setTextColor(Color.GRAY)

        rippleView.scaleX = 1f
        rippleView.scaleY = 1f
        val anim = ValueAnimator.ofFloat(1f, 2.2f)
        anim.duration = 900
        anim.repeatCount = ValueAnimator.INFINITE
        anim.addUpdateListener {
            if (!recording) return@addUpdateListener
            val v = it.animatedValue as Float
            rippleView.scaleX = v
            rippleView.scaleY = v
            rippleDrawable.alpha = max(0, 255 - ((v - 1f) / 1.2f * 255).toInt())
        }
        anim.start()
        micButton.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start()
    }

    private fun hideRecordingUi() {
        waveformRow.visibility = View.GONE
        timerLabel.visibility = View.GONE
        trashIcon.visibility = View.GONE
        hintLabel.visibility = View.GONE
        rippleView.scaleX = 0f
        rippleView.scaleY = 0f
        micButton.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
    }

    private fun startTimerLoop() {
        val updater = object : Runnable {
            override fun run() {
                if (!recording) return
                val elapsed = System.currentTimeMillis() - startTimeMs
                if (elapsed >= MAX_RECORD_MS) {
                    stopRecording(false)
                    return
                }
                val totalSeconds = (elapsed / 1000).toInt()
                timerLabel.text = String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
                handler.postDelayed(this, 200)
            }
        }
        timerUpdater = updater
        handler.post(updater)
    }

    private fun startWaveformLoop() {
        val updater = object : Runnable {
            override fun run() {
                if (!recording) return
                val amp = try {
                    recorder?.maxAmplitude ?: 0
                } catch (ignored: Throwable) {
                    0
                }
                val normalized = min(1f, amp / 12000f)
                val bar = waveformBars.random()
                val height = dp(6) + (normalized * dp(26)).toInt()
                val lp = bar.layoutParams
                lp.height = height
                bar.layoutParams = lp
                handler.postDelayed(this, 90)
            }
        }
        waveformUpdater = updater
        handler.post(updater)
    }
}
