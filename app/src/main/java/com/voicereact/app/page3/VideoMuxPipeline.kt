package com.voicereact.app.page3

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

object VideoMuxPipeline {

    data class Result(val success: Boolean, val log: String)

    /**
     * videoOnly: فيديو H.264 بدون صوت (طوله = صوت المستخدم + ذيل التوقيع)
     * voice: التسجيل الأصلي بدون أي تغيير بالسرعة أو الطبقة
     * signature: نغمة Echo Signature تُوضع بعد نهاية صوت المستخدم مباشرة
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

        val voiceMs = (voiceSeconds * 1000f).roundToInt()
        val filter = String.format(
            Locale.US,
            "[1:a]atrim=0:%.3f,asetpts=PTS-STARTPTS,apad=whole_dur=%.3f[v];" +
                "[2:a]volume=0.6,adelay=%d:all=1,apad=whole_dur=%.3f[s];" +
                "[v][s]amix=inputs=2:duration=first:normalize=0:dropout_transition=0[aout]",
            voiceSeconds, totalSeconds, voiceMs, totalSeconds
        )

        val args = arrayOf(
            "-y",
            "-i", videoOnly.absolutePath,
            "-i", voice.absolutePath,
            "-i", signature.absolutePath,
            "-filter_complex", filter,
            "-map", "0:v:0",
            "-map", "[aout]",
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
