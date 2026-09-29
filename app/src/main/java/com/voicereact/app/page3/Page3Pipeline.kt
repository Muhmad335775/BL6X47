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
        try {
            workDir.mkdirs()
            diagnostics.setProgress(0)
            diagnostics.setRender(IndicatorState.WORKING, "الرندر: تجهيز المحرك")
            diagnostics.setAudio(IndicatorState.WORKING, "الصوت: فك الترميز")
            diagnostics.setBody(IndicatorState.WORKING, "حركة الجسد: تحميل الشخصية")
            diagnostics.log("الشخصية ${input.characterNumber} ← ${input.characterNumber}m/m.vrm")
            diagnostics.log("ملف الصوت: ${input.recordedAudioFile.absolutePath}")

            if (!input.recordedAudioFile.exists()) {
                throw IllegalStateException("ملف الصوت غير موجود")
            }

            val pcm = AudioDecoder.decode(input.recordedAudioFile)
            val voiceSeconds = min(pcm.samples.size.toFloat() / pcm.sampleRate, MAX_VOICE_SECONDS)
            var peak = 0
            for (s in pcm.samples) {
                val a = abs(s.toInt())
                if (a > peak) peak = a
            }
            val audioOk = voiceSeconds >= 0.3f && peak >= 300
            diagnostics.log("الصوت: مدة=${f2(voiceSeconds)}ث ، ذروة=$peak ، تردد=${pcm.sampleRate}")
            if (audioOk) {
                diagnostics.setAudio(IndicatorState.OK, "الصوت: مقروء (${f1(voiceSeconds)}ث)")
            } else {
                diagnostics.setAudio(IndicatorState.FAIL, "الصوت: صامت أو قصير جداً (ذروة=$peak)")
            }

            val envelope = AudioEnvelope.build(pcm.samples, pcm.sampleRate, ENVELOPE_WINDOW)
            val totalSeconds = voiceSeconds + TAIL_SECONDS
            val totalFrames = ceil(totalSeconds * FPS).toInt()
            val pitchTrack = PitchTrack.build(pcm.samples, pcm.sampleRate, FPS, totalFrames)
            diagnostics.log("مخطط: صوت=${f2(voiceSeconds)}ث + ذيل=${f2(TAIL_SECONDS)}ث = ${f2(totalSeconds)}ث ، فريمات=$totalFrames")

            val r = CinematicRenderer(WIDTH, HEIGHT)
            renderer = r
            val videoOnly = File(workDir, "video_only.mp4")
            val enc = FrameEncoder(WIDTH, HEIGHT, FPS, BITRATE, videoOnly)
            encoder = enc

            val surface = enc.start()
            r.attachSurface(surface)
            r.loadCharacter(context.assets, input.characterNumber)
            val (mr, mg, mb) = input.moodColor
            r.setMoodLightColor(mr, mg, mb)
            r.setAudioEnvelope(envelope, ENVELOPE_WINDOW)
            r.setPitchTrack(pitchTrack, FPS)

            val dropCm = r.armDropMeters * 100f
            diagnostics.log("العظام الموجودة: ${r.foundBoneCount}/9 ، نزول الذراع: ${f1(dropCm)} سم")
            diagnostics.setBody(
                IndicatorState.WORKING,
                "حركة الجسد: عظام ${r.foundBoneCount}/9 ، نزول الذراع ${f1(dropCm)} سم"
            )
            diagnostics.setRender(IndicatorState.WORKING, "الرندر: 0/$totalFrames")

            val frameNanos = 1_000_000_000L / FPS
            val startNanos = System.nanoTime()
            var renderedFrames = 0
            for (frame in 0 until totalFrames) {
                if (cancelled) throw IllegalStateException("تم الإلغاء")

                val due = startNanos + frame.toLong() * frameNanos
                while (System.nanoTime() < due) {
                    enc.drain()
                    Thread.sleep(1)
                }

                val t = frame / FPS.toFloat()
                r.renderFrame(t, voiceSeconds, input.surpriseBeatAtSeconds) { enc.drain() }
                enc.drain()
                renderedFrames++
                if (frame % 10 == 0) {
                    diagnostics.setProgress((frame * 90) / totalFrames)
                    diagnostics.setRender(IndicatorState.WORKING, "الرندر: $frame/$totalFrames")
                }
            }
            val renderSeconds = (System.nanoTime() - startNanos) / 1_000_000_000f

            val motionCm = r.bodyMotionMeters * 100f
            val bonesFound = r.foundBoneCount
            val bodyOk = bonesFound >= 6 && r.armDropMeters > 0.05f && r.bodyMotionMeters > 0.01f
            val bodyLabel = "عظام $bonesFound/9 ، نزول ${f1(dropCm)} سم ، حركة ${f1(motionCm)} سم"
            if (bodyOk) {
                diagnostics.setBody(IndicatorState.OK, "حركة الجسد: $bodyLabel")
            } else {
                diagnostics.setBody(IndicatorState.FAIL, "حركة الجسد متجمدة: $bodyLabel")
            }

            diagnostics.setRender(IndicatorState.WORKING, "الرندر: إنهاء الترميز")
            r.detachSurface()
            enc.finish()
            diagnostics.log(
                "رندر=$renderedFrames فريم ، مكتوب=${enc.writtenFrames} من $totalFrames ، زمن الرندر=${f1(renderSeconds)}ث"
            )

            diagnostics.setProgress(93)
            diagnostics.setRender(IndicatorState.WORKING, "الرندر: دمج الصوت")
            val signature = File(workDir, "echo_signature.wav")
            EchoSignatureGenerator.generate(pcm.samples, pcm.sampleRate, signature)

            val finalFile = File(workDir, "final_${input.characterNumber}m.mp4")
            val result = VideoMuxPipeline.muxFinal(
                videoOnly, input.recordedAudioFile, signature, finalFile, voiceSeconds, totalSeconds
            )
            if (!result.success || !finalFile.exists() || finalFile.length() < 1000L) {
                throw IllegalStateException("فشل دمج ffmpeg:\n" + result.log.takeLast(1800))
            }

            val (hasVideo, hasAudio, durationSeconds) = inspect(finalFile)
            diagnostics.log(
                "الناتج: فيديو=$hasVideo صوت=$hasAudio مدة=${f2(durationSeconds)}ث حجم=${finalFile.length() / 1024}KB"
            )
            if (!hasVideo) throw IllegalStateException("الملف النهائي بدون مسار فيديو")
            if (hasAudio && audioOk) {
                diagnostics.setAudio(IndicatorState.OK, "الصوت: مدموج ✓ (${f1(durationSeconds)}ث)")
            } else if (!hasAudio) {
                diagnostics.setAudio(IndicatorState.FAIL, "الصوت: الملف النهائي بدون مسار صوت")
            }

            diagnostics.log("المسار: ${finalFile.absolutePath}")
            diagnostics.finalVideoPath = finalFile.absolutePath
            diagnostics.setProgress(100)
            diagnostics.setRender(IndicatorState.OK, "الرندر: اكتمل ✓ (${enc.writtenFrames} فريم)")
            return finalFile
        } catch (e: Throwable) {
            val trace = Log.getStackTraceString(e)
            Log.e("Page3Pipeline", "render failed", e)
            diagnostics.log(trace.take(2500))
            diagnostics.log("خطأ: ${e.javaClass.simpleName}: ${(e.message ?: "").take(400)}")
            diagnostics.setRender(IndicatorState.FAIL, "الرندر: فشل — ${e.javaClass.simpleName}")
            return null
        } finally {
            try { renderer?.destroy() } catch (ignored: Throwable) { }
            try { encoder?.release() } catch (ignored: Throwable) { }
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
