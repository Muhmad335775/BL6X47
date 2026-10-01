package com.voicereact.app.page3

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

object VideoMuxPipeline {

    data class Result(val success: Boolean, val log: String)

    /**
     * videoOnly: H.264 video with no audio track
     * voice: the final character-voice audio (your recording, tone-converted) — this is the ONLY
     *        audio source muxed in now. No synthesized end-of-clip tone is added anymore.
     */
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
