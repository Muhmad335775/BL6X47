package com.voicereact.app.page3

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File

object VideoMuxPipeline {

    fun muxFinal(
        rawVideoNoAudio: File,
        originalAudio: File,
        echoSignatureTone: File,
        finalOutput: File,
        onComplete: (success: Boolean, log: String) -> Unit
    ) {
        if (finalOutput.exists()) finalOutput.delete()

        val audioFilter =
            "[1:a]afade=t=out:st=0:d=0.05[a1];" +
            "[2:a]volume=0.35,adelay=0|0[sig];" +
            "[a1][sig]amix=inputs=2:duration=first:dropout_transition=0[aout]"

        val cmd = arrayOf(
            "-y",
            "-i", rawVideoNoAudio.absolutePath,
            "-i", originalAudio.absolutePath,
            "-i", echoSignatureTone.absolutePath,
            "-filter_complex", audioFilter,
            "-map", "0:v",
            "-map", "[aout]",
            "-c:v", "copy",
            "-c:a", "aac", "-b:a", "128k",
            "-shortest",
            finalOutput.absolutePath
        ).joinToString(" ")

        FFmpegKit.executeAsync(cmd) { session ->
            val ok = ReturnCode.isSuccess(session.returnCode)
            onComplete(ok, session.allLogsAsString)
        }
    }
}
