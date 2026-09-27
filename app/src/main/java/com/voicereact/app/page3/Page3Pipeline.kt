package com.voicereact.app.page3

import android.content.Context
import com.google.android.filament.gltfio.UbershaderProvider
import java.io.File
import kotlin.math.min

class Page3Pipeline(private val context: Context) {

    data class Page2Output(
        val characterNumber: Int,
        val recordedAudioFile: File,
        val moodColor: Triple<Float, Float, Float>,
        val surpriseBeatAtSeconds: Float?,
    )

    fun render(input: Page2Output, workDir: File, onFinished: (File?) -> Unit) {
        val durationSeconds = min(getAudioDurationSeconds(input.recordedAudioFile), 20f)
        val fps = 30
        val width = 1080
        val height = 1920

        val rawVideo = File(workDir, "raw_video.h264")
        val signatureTone = File(workDir, "echo_signature.wav")
        val finalOutput = File(workDir, "final_${input.characterNumber}m.mp4")

        val renderer = CinematicRenderer(width, height, fps, durationSeconds)
        val encoder = FrameEncoder(width, height, fps, outputRawVideo = rawVideo)

        val surface = encoder.start()
        renderer.attachSurface(surface)
        renderer.loadCharacter(context.assets, input.characterNumber, UbershaderProvider(renderer.engine))
        val (r, g, b) = input.moodColor
        renderer.setMoodLightColor(r, g, b)

        val totalFrames = (durationSeconds * fps).toInt()
        for (frame in 0 until totalFrames) {
            val t = frame / fps.toFloat()
            val isSurpriseBeat = input.surpriseBeatAtSeconds?.let { abs(t - it) < (1f / fps) } ?: false
            renderer.renderFrame(t, isSurpriseBeat)
            encoder.drainEncoder(endOfStream = false)
        }
        encoder.stop()
        renderer.destroy()

        val pcm = decodeToPcm16(input.recordedAudioFile)
        EchoSignatureGenerator.generate(pcm.samples, pcm.sampleRate, signatureTone)

        VideoMuxPipeline.muxFinal(
            rawVideoNoAudio = rawVideo,
            originalAudio = input.recordedAudioFile,
            echoSignatureTone = signatureTone,
            finalOutput = finalOutput
        ) { success, _ ->
            onFinished(if (success) finalOutput else null)
        }
    }

    private fun abs(v: Float) = if (v < 0) -v else v

    data class PcmData(val samples: ShortArray, val sampleRate: Int)

    private fun decodeToPcm16(input: File): PcmData {
        TODO("فك الصوت الأصلي حسب الفورمات الجاي من صفحة 1 (wav/aac)")
    }

    private fun getAudioDurationSeconds(file: File): Float {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val ms = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return ms / 1000f
        } finally {
            mmr.release()
        }
    }
}
