package com.voicereact.app.page3

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

object VideoMuxPipeline {

    data class Result(val success: Boolean, val log: String)

    private const val WATERMARK_X =
        "if(lt(mod(t\\,8)\\,2)\\,20\\,if(lt(mod(t\\,8)\\,4)\\,(w-tw-20)\\,if(lt(mod(t\\,8)\\,6)\\,(w-tw-20)\\,20)))"
    private const val WATERMARK_Y =
        "if(lt(mod(t\\,8)\\,2)\\,20\\,if(lt(mod(t\\,8)\\,4)\\,20\\,if(lt(mod(t\\,8)\\,6)\\,(h-th-20)\\,(h-th-20))))"
    private const val WATERMARK_FILTER =
        "drawtext=fontfile=/system/fonts/Roboto-Regular.ttf:text='VoiceBL7X4':fontsize=20:" +
            "fontcolor=white@0.85:shadowcolor=black@0.6:shadowx=1:shadowy=1:" +
            "x=$WATERMARK_X:y=$WATERMARK_Y"

    fun muxFinal(
        videoOnly: File,
        voice: File,
        signature: File,
        finalOutput: File,
        voiceSeconds: Float,
        totalSeconds: Float
    ): Result {
        if (finalOutput.exists()) finalOutput.delete()

        val args = arrayOf(
            "-y",
            "-i", videoOnly.absolutePath,
            "-i", voice.absolutePath,
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-vf", WATERMARK_FILTER,
            "-c:v", "h264_mediacodec",
            "-b:v", "6M",
            "-c:a", "aac",
            "-b:a", "128k",
            "-ar", "44100",
            "-t", String.format(Locale.US, "%.3f", totalSeconds),
            "-movflags", "+faststart",
            finalOutput.absolutePath
        )

        val session = FFmpegKit.executeWithArguments(args)
        val ok = ReturnCode.isSuccess(session.returnCode)
        val log = session.allLogsAsString ?: ""
        return Result(ok, log)
    }
}
