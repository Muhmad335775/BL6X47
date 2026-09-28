package com.voicereact.app.page3

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import com.google.android.filament.utils.Utils
import java.io.File

class Page3Activity : Activity() {

    companion object {
        init {
            Utils.init()
        }

        private const val EXTRA_CHARACTER = "page3_character"
        private const val EXTRA_AUDIO = "page3_audio"
        private const val EXTRA_MOOD_R = "page3_mood_r"
        private const val EXTRA_MOOD_G = "page3_mood_g"
        private const val EXTRA_MOOD_B = "page3_mood_b"
        private const val EXTRA_SURPRISE = "page3_surprise"

        fun start(
            context: Context,
            characterNumber: Int,
            audioPath: String,
            moodR: Float = 1.0f,
            moodG: Float = 0.85f,
            moodB: Float = 0.75f,
            surpriseAtSeconds: Float? = null
        ) {
            val intent = Intent(context, Page3Activity::class.java)
            intent.putExtra(EXTRA_CHARACTER, characterNumber)
            intent.putExtra(EXTRA_AUDIO, audioPath)
            intent.putExtra(EXTRA_MOOD_R, moodR)
            intent.putExtra(EXTRA_MOOD_G, moodG)
            intent.putExtra(EXTRA_MOOD_B, moodB)
            intent.putExtra(EXTRA_SURPRISE, surpriseAtSeconds ?: -1f)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private lateinit var diagnostics: Page3Diagnostics
    private var pipeline: Page3Pipeline? = null
    private var worker: Thread? = null
    private var videoStarted = false

    private lateinit var videoView: VideoView
    private lateinit var titleView: TextView
    private lateinit var audioDot: View
    private lateinit var audioLabel: TextView
    private lateinit var bodyDot: View
    private lateinit var bodyLabel: TextView
    private lateinit var renderDot: View
    private lateinit var renderLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        diagnostics = Page3Diagnostics { d ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) refresh(d)
            }
        }

        val characterNumber = intent.getIntExtra(EXTRA_CHARACTER, 0)
        val audioPath = intent.getStringExtra(EXTRA_AUDIO) ?: ""
        val moodR = intent.getFloatExtra(EXTRA_MOOD_R, 1.0f)
        val moodG = intent.getFloatExtra(EXTRA_MOOD_G, 0.85f)
        val moodB = intent.getFloatExtra(EXTRA_MOOD_B, 0.75f)
        val surprise = intent.getFloatExtra(EXTRA_SURPRISE, -1f)

        val baseInput = Page3Pipeline.Input(
            characterNumber = characterNumber,
            recordedAudioFile = File(audioPath),
            moodColor = Triple(moodR, moodG, moodB),
            surpriseBeatAtSeconds = if (surprise >= 0f) surprise else null
        )
        val p = Page3Pipeline(this, diagnostics)
        pipeline = p
        val workDir = File(filesDir, "page3")

        val t = Thread(null, Runnable {
            val finalAudio = buildEchoAudio(File(audioPath))
            p.run(baseInput.copy(recordedAudioFile = finalAudio), workDir)
        }, "page3-worker", 16L * 1024L * 1024L)
        worker = t
        t.start()
    }

    /** يحوّل تسجيلك لنص (Vosk) ثم يولّد صوت رد جديد يقول نفس الكلام (TextToSpeech)؛ لو فشل يرجّع تسجيلك الأصلي */
    private fun buildEchoAudio(original: File): File {
        return try {
            diagnostics.setAudio(IndicatorState.WORKING, "الصوت: تفريغ الكلام لنص")
            val pcm = AudioDecoder.decode(original)
            val text = VoskSpeechEngine.recognize(this, pcm.samples, pcm.sampleRate)
            diagnostics.log("النص المفهوم: $text")
            if (text.isBlank()) {
                diagnostics.log("تحذير: ما انفهم كلام واضح، رح يشتغل بصوتك الأصلي")
                return original
            }
            diagnostics.setAudio(IndicatorState.WORKING, "الصوت: توليد رد الشخصية")
            val ttsFile = File(filesDir, "page3_echo.wav")
            val synth = TtsEchoSynthesizer(this)
            val ok = synth.synthesizeToFile(text, ttsFile)
            synth.release()
            if (ok) ttsFile else original
        } catch (e: Throwable) {
            diagnostics.log("فشل تحويل الكلام: ${e.javaClass.simpleName}: ${e.message}")
            original
        }
    }

    override fun onDestroy() {
        pipeline?.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun colorOf(state: IndicatorState): Int = when (state) {
        IndicatorState.IDLE -> Color.parseColor("#8A9099")
        IndicatorState.WORKING -> Color.parseColor("#FF9800")
        IndicatorState.OK -> Color.parseColor("#2ECC71")
        IndicatorState.FAIL -> Color.parseColor("#F44336")
    }

    private fun addCard(parent: LinearLayout, initialText: String): Pair<View, TextView> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(9), dp(12), dp(9))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#DD1F2630"))
        bg.cornerRadius = dp(12).toFloat()
        row.background = bg

        val dot = View(this)
        val dotBg = GradientDrawable()
        dotBg.shape = GradientDrawable.OVAL
        dotBg.setColor(colorOf(IndicatorState.IDLE))
        dot.background = dotBg
        val dotParams = LinearLayout.LayoutParams(dp(18), dp(18))
        dotParams.marginEnd = dp(12)
        row.addView(dot, dotParams)

        val label = TextView(this)
        label.setTextColor(Color.WHITE)
        label.textSize = 14f
        label.text = initialText
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val rowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowParams.topMargin = dp(6)
        parent.addView(row, rowParams)
        return Pair(dot, label)
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0E1116"))

        videoView = VideoView(this)
        videoView.visibility = View.GONE
        root.addView(
            videoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(14), dp(36), dp(14), dp(12))
        panel.setBackgroundColor(Color.parseColor("#66000000"))

        titleView = TextView(this)
        titleView.setTextColor(Color.WHITE)
        titleView.textSize = 17f
        titleView.setTypeface(Typeface.DEFAULT_BOLD)
        titleView.text = "لوحة فحص الصفحة 3"
        panel.addView(titleView)

        val audio = addCard(panel, "الصوت: بانتظار البدء")
        audioDot = audio.first
        audioLabel = audio.second

        val body = addCard(panel, "حركة الجسد: بانتظار البدء")
        bodyDot = body.first
        bodyLabel = body.second

        val render = addCard(panel, "الرندر: بانتظار البدء")
        renderDot = render.first
        renderLabel = render.second

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        val pbParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        pbParams.topMargin = dp(8)
        panel.addView(progressBar, pbParams)

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        logView = TextView(this)
        logView.setTextColor(Color.parseColor("#C9D1D9"))
        logView.textSize = 10.5f
        logView.setTypeface(Typeface.MONOSPACE)
        logView.setPadding(dp(8), dp(6), dp(8), dp(6))

        logScroll = ScrollView(this)
        logScroll.setBackgroundColor(Color.parseColor("#B3000000"))
        logScroll.addView(logView)
        val logParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(150),
            Gravity.BOTTOM
        )
        logParams.bottomMargin = dp(70)
        root.addView(logScroll, logParams)

        setContentView(root)
    }

    private fun setIndicator(dot: View, label: TextView, state: IndicatorState, text: String) {
        (dot.background as GradientDrawable).setColor(colorOf(state))
        label.text = text
    }

    private fun refresh(d: Page3Diagnostics) {
        setIndicator(audioDot, audioLabel, d.audioState, d.audioText)
        setIndicator(bodyDot, bodyLabel, d.bodyState, d.bodyText)
        setIndicator(renderDot, renderLabel, d.renderState, d.renderText)
        progressBar.progress = d.progress
        logView.text = d.logText()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        val path = d.finalVideoPath
        if (d.renderState == IndicatorState.OK && path != null && !videoStarted) {
            playVideo(path)
        }
    }

    private fun playVideo(path: String) {
        videoStarted = true
        titleView.visibility = View.GONE
        progressBar.visibility = View.GONE

        videoView.visibility = View.VISIBLE
        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener { it.isLooping = true }
        videoView.setOnErrorListener { _, what, extra ->
            diagnostics.log("خطأ المشغل: $what / $extra")
            true
        }
        videoView.setVideoPath(path)
        videoView.start()
    }
}
