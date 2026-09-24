package com.voicereact.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity(), RecorderButtonView.Listener {

    private lateinit var micButton: RecorderButtonView
    private lateinit var waveform: WaveformView
    private lateinit var timerText: TextView
    private lateinit var cancelHint: TextView

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var elapsedSeconds = 0
    private val maxSeconds = 20
    private val tickHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var permissionRequestInFlight = false

    private val requestMicPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            permissionRequestInFlight = false
            if (granted) {
                startRecording()
            } else {
                showPermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        micButton = findViewById(R.id.micButton)
        waveform = findViewById(R.id.waveform)
        timerText = findViewById(R.id.timerText)
        cancelHint = findViewById(R.id.cancelHint)

        micButton.listener = this

        if (!hasMicPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissionSafely()
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionSafely() {
        if (permissionRequestInFlight) return
        permissionRequestInFlight = true
        try {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        } catch (e: Exception) {
            permissionRequestInFlight = false
        }
    }

    override fun onPressStart() {
        if (!hasMicPermission()) {
            requestPermissionSafely()
            return
        }
        startRecording()
    }

    override fun onSwipeCancel() {
        stopRecordingInternal(delete = true)
    }

    override fun onRelease(committed: Boolean) {
        stopRecordingInternal(delete = !committed)
    }

    private fun startRecording() {
        if (running) return
        if (!hasMicPermission()) return

        val clipsDir = File(filesDir, "clips").apply { mkdirs() }
        val file = File(clipsDir, "clip_${System.currentTimeMillis()}.m4a")
        outputFile = file

        val newRecorder = MediaRecorder()
        try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioSamplingRate(44100)
            newRecorder.setAudioEncodingBitRate(128000)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show()
            try { newRecorder.release() } catch (ignored: Exception) {}
            return
        }
        recorder = newRecorder

        elapsedSeconds = 0
        running = true
        micButton.setRecordingState(true)
        waveform.reset()
        timerText.text = formatTime(0)
        timerText.visibility = TextView.VISIBLE
        waveform.visibility = TextView.VISIBLE
        cancelHint.visibility = TextView.VISIBLE

        tickHandler.post(tickRunnable)
        tickHandler.postDelayed(secondRunnable, 1000)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val amp = try {
                recorder?.maxAmplitude ?: 0
            } catch (e: Exception) {
                0
            }
            waveform.pushAmplitude(if (amp > 0) amp else WaveformView.simulatedTick())

            if (elapsedSeconds >= maxSeconds) {
                stopRecordingInternal(delete = false)
                return
            }
            tickHandler.postDelayed(this, 200)
        }
    }

    private val secondRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            elapsedSeconds += 1
            timerText.text = formatTime(elapsedSeconds)
            if (elapsedSeconds < maxSeconds) {
                tickHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun stopRecordingInternal(delete: Boolean) {
        if (!running) return
        running = false
        micButton.setRecordingState(false)
        tickHandler.removeCallbacksAndMessages(null)

        try {
            recorder?.stop()
        } catch (e: Exception) {
        }
        try {
            recorder?.release()
        } catch (e: Exception) {
        }
        recorder = null

        timerText.visibility = TextView.INVISIBLE
        waveform.visibility = TextView.INVISIBLE
        cancelHint.visibility = TextView.INVISIBLE

        if (delete) {
            outputFile?.delete()
            outputFile = null
        } else {
            outputFile?.let { onClipReady(it) }
        }
    }

    private fun onClipReady(file: File) {
    }

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun showPermissionDenied() {
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.RECORD_AUDIO
        )
        if (!shouldShowRationale) {
            Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (e: Exception) {
            }
        } else {
            Toast.makeText(this, R.string.mic_permission_rationale, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacksAndMessages(null)
        try { recorder?.release() } catch (e: Exception) {}
        recorder = null
    }
}
