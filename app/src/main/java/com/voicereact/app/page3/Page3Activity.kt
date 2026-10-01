package com.voicereact.app.page3

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        diagnostics = Page3Diagnostics { d ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) checkVideoReady(d)
            }
        }

        val characterNumber = intent.getIntExtra(EXTRA_CHARACTER, 0)
        val audioPath = intent.getStringExtra(EXTRA_AUDIO) ?: ""
        val moodR = intent.getFloatExtra(EXTRA_MOOD_R, 1.0f)
        val moodG = intent.getFloatExtra(EXTRA_MOOD_G, 0.85f)
        val moodB = intent.getFloatExtra(EXTRA_MOOD_B, 0.75f)
        val surprise = intent.getFloatExtra(EXTRA_SURPRISE, -1f)

        val input = Page3Pipeline.Input(
            characterNumber = characterNumber,
            recordedAudioFile = File(audioPath),
            moodColor = Triple(moodR, moodG, moodB),
            surpriseBeatAtSeconds = if (surprise >= 0f) surprise else null
        )
        val p = Page3Pipeline(this, diagnostics)
        pipeline = p
        val workDir = File(filesDir, "page3")

        val t = Thread(null, Runnable { p.run(input, workDir) }, "page3-worker", 16L * 1024L * 1024L)
        worker = t
        t.start()
    }

    override fun onDestroy() {
        pipeline?.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        videoView = VideoView(this)
        root.addView(
            videoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        setContentView(root)
    }

    private fun checkVideoReady(d: Page3Diagnostics) {
        val path = d.finalVideoPath
        if (d.renderState == IndicatorState.OK && path != null && !videoStarted) {
            playVideo(path)
        }
    }

    private fun playVideo(path: String) {
        videoStarted = true
        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener { it.isLooping = true }
        videoView.setOnErrorListener { _, _, _ -> true }
        videoView.setVideoPath(path)
        videoView.start()
    }
}
