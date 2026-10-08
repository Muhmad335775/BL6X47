package com.voicereact.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.voicereact.app.page3.CharacterPicker
import com.voicereact.app.page3.Page3Activity
import java.io.File

class CinematicRecordingActivity : Activity() {

    companion object {
        const val EXTRA_AUDIO_PATH = "extra_audio_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
        if (audioPath == null || !File(audioPath).exists()) {
            Toast.makeText(this, "No recorded audio", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val num = CharacterPicker.next()
        Page3Activity.start(this, num, audioPath)
        finish()
    }
}
