package com.voicereact.app.page3

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class FrameEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int = 8_000_000,
    private val outputRawVideo: File
) {
    private lateinit var codec: MediaCodec
    lateinit var inputSurface: Surface
        private set
    private val stopped = AtomicBoolean(false)
    private val rawWriter by lazy { outputRawVideo.outputStream() }

    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        return inputSurface
    }

    fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) codec.signalEndOfInputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { }
                outIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        val bytes = ByteArray(bufferInfo.size)
                        encodedData.get(bytes)
                        rawWriter.write(bytes)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    fun stop() {
        if (stopped.getAndSet(true)) return
        drainEncoder(endOfStream = true)
        rawWriter.flush()
        rawWriter.close()
        codec.stop()
        codec.release()
    }
}
