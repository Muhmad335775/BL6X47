package com.voicereact.app.page3

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TtsEchoSynthesizer(context: Context) {

    private var tts: TextToSpeech? = null
    private val readyLatch = CountDownLatch(1)

    init {
        tts = TextToSpeech(context) { readyLatch.countDown() }
    }

    fun synthesizeToFile(text: String, outFile: File): Boolean {
        readyLatch.await(5, TimeUnit.SECONDS)
        val engine = tts ?: return false
        var success = false
        val doneLatch = CountDownLatch(1)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                success = true
                doneLatch.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                doneLatch.countDown()
            }
        })

        if (outFile.exists()) outFile.delete()
        val id = "page3_echo_${System.currentTimeMillis()}"
        val result = engine.synthesizeToFile(text, Bundle(), outFile, id)
        if (result != TextToSpeech.SUCCESS) return false
        doneLatch.await(30, TimeUnit.SECONDS)
        return success && outFile.exists() && outFile.length() > 500L
    }

    fun release() {
        try { tts?.stop() } catch (ignored: Throwable) { }
        try { tts?.shutdown() } catch (ignored: Throwable) { }
    }
}
