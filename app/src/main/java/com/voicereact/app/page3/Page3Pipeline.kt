package com.voicereact.app.page3

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

class Page3Pipeline(
    private val context: Context,
    private val diagnostics: Page3Diagnostics
) {

    data class Input(
        val characterNumber: Int,
        val recordedAudioFile: File,
        val moodColor: Triple<Float, Float, Float>,
        val surpriseBeatAtSeconds: Float?
    )

    companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val FPS = 30
        const val BITRATE = 6_000_000
        const val TAIL_SECONDS = 1.2f
        const val MAX_VOICE_SECONDS = 20f
        const val ENVELOPE_WINDOW = 0.03f
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    private fun f1(v: Float): String = String.format(Locale.US, "%.1f", v)
    private fun f2(v: Float): String = String.format(Locale.US, "%.2f", v)

    fun run(input: Input, workDir: File): File? {
        var renderer: CinematicRenderer? = null
        var encoder: FrameEncoder? = null
        var toneConverter: OpenVoiceToneConverter? = null
        try {
            workDir.mkdirs()
            diagnostics.setProgress(0)
            diagnostics.setRender(IndicatorState.WORKING, "Render: preparing engine")
            diagnostics.setAudio(IndicatorState.WORKING, "Audio: loading OpenVoice models")
            diagnostics.setBody(IndicatorState.WORKING, "Body motion: loading character")
            diagnostics.log("Character ${input.characterNumber} -> ${input.characterNumber}m/m.vrm")

            if (!input.recordedAudioFile.exists()) {
                throw IllegalStateException("Audio file not found")
            }

            val originalPcm = AudioDecoder.decode(input.recordedAudioFile)

            val converter = OpenVoiceToneConverter(context)
            toneConverter = converter
            converter.loadModels { msg -> diagnostics.logSticky(msg) }

            val userFloats = FloatArray(originalPcm.samples.size) { originalPcm.samples[it] / 32768f }
            diagnostics.setAudio(IndicatorState.WORKING, "Audio: converting to character voice (OpenVoice)")
            val convertedFloats = converter.convert(userFloats, originalPcm.sampleRate, input.characterNumber) { msg ->
                diagnostics.logSticky(msg)
            }
            val convertedShorts = converter.floatsToShorts(convertedFloats)
            val characterVoiceFile = File(workDir, "character_voice.wav")
            EchoSignatureGenerator.writeWavPublic(convertedShorts, originalPcm.sampleRate, characterVoiceFile)

            val pcm = AudioDecoder.Pcm16(convertedShorts, originalPcm.sampleRate, 1)
            val voiceSeconds = min(pcm.samples.size.toFloat() / pcm.sampleRate, MAX_VOICE_SECONDS)
            var peak = 0
            for (s in pcm.samples) {
                val a = abs(s.toInt())
                if (a > peak) peak = a
            }
            val audioOk = voiceSeconds >= 0.3f && peak >= 300
            diagnostics.log("Converted audio: duration=${f2(voiceSeconds)}s, peak=$peak")
            if (audioOk) {
                diagnostics.setAudio(IndicatorState.OK, "Audio: character voice ready (${f1(voiceSeconds)}s)")
            } else {
                diagnostics.setAudio(IndicatorState.FAIL, "Audio: silent or too short after conversion")
            }

            val envelope = AudioEnvelope.build(pcm.samples, pcm.sampleRate, ENVELOPE_WINDOW)
            val totalSeconds = voiceSeconds
            val totalFrames = ceil(totalSeconds * FPS).toInt().coerceAtLeast(1)
            val pitchTrack = PitchTrack.build(pcm.samples, pcm.sampleRate, FPS, totalFrames)

            val r = CinematicRenderer(WIDTH, HEIGHT)
            renderer = r
            val videoOnly = File(workDir, "video_only.mp4")
            val enc = FrameEncoder(WIDTH, HEIGHT, FPS, BITRATE, videoOnly)
            encoder = enc

            val surface = enc.start()
            r.attachSurface(surface)

            try {
                r.loadCharacter(context.assets, input.characterNumber)
            } catch (e: Throwable) {
                val trace = Log.getStackTraceString(e)
                diagnostics.setBody(IndicatorState.FAIL, "Body motion: character load failed — ${e.javaClass.simpleName}")
                diagnostics.logSticky("LOAD ERROR for character ${input.characterNumber}:\n${trace.take(900)}")
                throw e
            }

            val (mr, mg, mb) = input.moodColor
            r.setMoodLightColor(mr, mg, mb)
            r.setAudioEnvelope(envelope, ENVELOPE_WINDOW)
            r.setPitchTrack(pitchTrack, FPS)

            val dropCm = r.armDropMeters * 100f
            diagnostics.setBody(
                IndicatorState.WORKING,
                "Body motion: bones ${r.foundBoneCount}/13, arm drop ${f1(dropCm)}cm"
            )
            diagnostics.setRender(IndicatorState.WORKING, "Render: 0/$totalFrames")

            val frameNanos = 1_000_000_000L / FPS
            val startNanos = System.nanoTime()
            for (frame in 0 until totalFrames) {
                if (cancelled) throw IllegalStateException("Cancelled")
                val due = startNanos + frame.toLong() * frameNanos
                while (System.nanoTime() < due) {
                    enc.drain()
                    Thread.sleep(1)
                }
                val t = frame / FPS.toFloat()
                r.renderFrame(t, voiceSeconds, input.surpriseBeatAtSeconds) { enc.drain() }
                enc.drain()
                if (frame % 10 == 0) {
                    diagnostics.setProgress((frame * 90) / totalFrames)
                    diagnostics.setRender(IndicatorState.WORKING, "Render: $frame/$totalFrames")
                }
            }

            val motionCm = r.bodyMotionMeters * 100f
            val bonesFound = r.foundBoneCount
            val bodyOk = bonesFound >= 6 && r.armDropMeters > 0.05f && r.bodyMotionMeters > 0.01f
            if (bodyOk) {
                diagnostics.setBody(IndicatorState.OK, "Body motion: bones $bonesFound/13, drop ${f1(dropCm)}cm, motion ${f1(motionCm)}cm")
            } else {
                diagnostics.setBody(IndicatorState.FAIL, "Body motion frozen")
            }

            diagnostics.setRender(IndicatorState.WORKING, "Render: finalizing encoder")
            r.detachSurface()
            enc.finish()

            diagnostics.setProgress(93)
            diagnostics.setRender(IndicatorState.WORKING, "Render: muxing audio")

            val finalFile = File(workDir, "final_${input.characterNumber}m.mp4")
            val result = VideoMuxPipeline.muxFinal(
                videoOnly, characterVoiceFile, characterVoiceFile, finalFile, voiceSeconds, totalSeconds
            )
            if (!result.success || !finalFile.exists() || finalFile.length() < 1000L) {
                throw IllegalStateException("ffmpeg mux failed:\n" + result.log.takeLast(1800))
            }

            val (hasVideo, hasAudio, durationSeconds) = inspect(finalFile)
            if (!hasVideo) throw IllegalStateException("Final file has no video track")
            if (hasAudio && audioOk) {
                diagnostics.setAudio(IndicatorState.OK, "Audio: character voice muxed OK (${f1(durationSeconds)}s)")
            } else if (!hasAudio) {
                diagnostics.setAudio(IndicatorState.FAIL, "Audio: final file has no audio track")
            }

            diagnostics.finalVideoPath = finalFile.absolutePath
            diagnostics.setProgress(100)
            diagnostics.setRender(IndicatorState.OK, "Render: complete (${enc.writtenFrames} frames)")
            return finalFile
        } catch (e: Throwable) {
            val trace = Log.getStackTraceString(e)
            Log.e("Page3Pipeline", "render failed", e)
            diagnostics.logSticky("FULL ERROR:\n${trace.take(1200)}")
            diagnostics.setRender(IndicatorState.FAIL, "Render: failed - ${e.javaClass.simpleName}")
            return null
        } finally {
            try { renderer?.destroy() } catch (ignored: Throwable) { }
            try { encoder?.release() } catch (ignored: Throwable) { }
            try { toneConverter?.release() } catch (ignored: Throwable) { }
            renderer = null
            encoder = null
            toneConverter = null
        }
    }

    private fun inspect(file: File): Triple<Boolean, Boolean, Float> {
        var hasVideo = false
        var hasAudio = false
        var seconds = 0f
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
            }
        } finally {
            extractor.release()
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            seconds = ms / 1000f
        } finally {
            retriever.release()
        }
        return Triple(hasVideo, hasAudio, seconds)
    }
}
