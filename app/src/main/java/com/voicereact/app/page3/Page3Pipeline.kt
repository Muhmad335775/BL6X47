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
        val visemeTimeline: List<VisemeFrame>
    )

    fun render(input: Page2Output, workDir: File, onFinished: (File?) -> Unit) {
        val audioPcm = AudioDecoder.decode(input.recordedAudioFile)
        val durationSeconds = min(audioPcm.samples.size.toFloat() / audioPcm.sampleRate, 20f)
        val fps = 30
        val width = 1080
        val height = 1920
        val envelopeWindow = 0.03f

        val rawVideo = File(workDir, "raw_video.h264")
        val signatureTone = File(workDir, "echo_signature.wav")
        val finalOutput = File(workDir, "final_${input.characterNumber}m.mp4")

        val renderer = CinematicRenderer(width, height, fps)
        val encoder = FrameEncoder(width, height, fps, outputRawVideo = rawVideo)

        val surface = encoder.start()
        renderer.attachSurface(surface)
        renderer.loadCharacter(context.assets, input.characterNumber, UbershaderProvider(renderer.engine))
        renderer.setVisemeTimeline(input.visemeTimeline)
        renderer.setAudioEnvelope(AudioEnvelope.build(audioPcm.samples, audioPcm.sampleRate, envelopeWindow), envelopeWindow)
        val (r, g, b) = input.moodColor
        renderer.setMoodLightColor(r, g, b)

        val totalFrames = (durationSeconds * fps).toInt()
        for (frame in 0 until totalFrames) {
            val t = frame / fps.toFloat()
            val isSurpriseBeat = input.surpriseBeatAtSeconds?.let { kotlin.math.abs(t - it) < (1f / fps) } ?: false
            renderer.renderFrame(t, isSurpriseBeat)
            encoder.drainEncoder(endOfStream = false)
        }
        encoder.stop()
        renderer.destroy()

        EchoSignatureGenerator.generate(audioPcm.samples, audioPcm.sampleRate, signatureTone)

        VideoMuxPipeline.muxFinal(
            rawVideoNoAudio = rawVideo,
            originalAudio = input.recordedAudioFile,
            echoSignatureTone = signatureTone,
            finalOutput = finalOutput
        ) { success, _ ->
            onFinished(if (success) finalOutput else null)
        }
    }
}
