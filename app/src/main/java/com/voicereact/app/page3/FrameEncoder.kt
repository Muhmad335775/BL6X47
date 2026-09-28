package com.voicereact.app.page3

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

class FrameEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val outputFile: File
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameCounter = 0L
    private var released = false

    val writtenFrames: Long
        get() = frameCounter

    fun start(): Surface {
        if (outputFile.exists()) outputFile.delete()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val s = c.createInputSurface()
        c.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        codec = c
        inputSurface = s
        return s
    }

    /** سحب غير حابس من الكودك — يُستدعى بعد كل فريم */
    fun drain() {
        drainInternal(false)
    }

    /** إنهاء الترميز: إشارة نهاية الستريم + سحب الباقي + إغلاق الملف */
    fun finish() {
        val c = codec ?: return
        c.signalEndOfInputStream()
        drainInternal(true)
        release()
    }

    private fun drainInternal(untilEndOfStream: Boolean) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        var idleCount = 0
        while (true) {
            val index = c.dequeueOutputBuffer(info, if (untilEndOfStream) 10_000L else 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEndOfStream) return
                idleCount++
                if (idleCount > 300) return
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    val m = muxer ?: return
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
            } else if (index >= 0) {
                idleCount = 0
                val buffer = c.getOutputBuffer(index)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (buffer != null && info.size > 0 && !isConfig && muxerStarted) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    info.presentationTimeUs = frameCounter * 1_000_000L / fps
                    frameCounter++
                    muxer?.writeSampleData(trackIndex, buffer, info)
                }
                c.releaseOutputBuffer(index, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        try {
            if (muxerStarted) muxer?.stop()
        } catch (ignored: Throwable) {
        }
        try { muxer?.release() } catch (ignored: Throwable) { }
        try { codec?.stop() } catch (ignored: Throwable) { }
        try { codec?.release() } catch (ignored: Throwable) { }
        try { inputSurface?.release() } catch (ignored: Throwable) { }
    }
}
