package com.voicereact.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.io.File

class CinematicRecordingActivity : Activity() {

    companion object {
        const val EXTRA_AUDIO_PATH = "extra_audio_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
        if (audioPath == null || !File(audioPath).exists()) {
            finish()
            return
        }

        val i = Intent(this, com.voicereact.app.page3.Page3Activity::class.java)
        i.putExtra("audio_path", audioPath)
        startActivity(i)
        finish()
    }
}
