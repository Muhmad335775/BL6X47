package com.voicereact.app.page3

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

object VideoMuxPipeline {

    data class Result(val success: Boolean, val log: String)

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
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "320k",
            "-ar", "48000",
            "-ac", "2",
            // فلتر speechnorm: يرفع الصوت البشري تلقائياً لأعلى مستوى بدون تشويه
            "-af", "speechnorm=e=12.5:r=0.0001:l=1",
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
