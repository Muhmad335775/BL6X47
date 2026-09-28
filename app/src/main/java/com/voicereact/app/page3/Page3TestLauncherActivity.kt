package com.voicereact.app.page3

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Random

class Page3TestLauncherActivity : Activity() {

    private val pickRequest = 4711
    private val permissionRequest = 4712
    private val maxRecordMillis = 20_000L

    private lateinit var status: TextView
    private lateinit var recordButton: Button

    private var recorder: MediaRecorder? = null
    private var recording = false
    private var recordFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable {
        if (recording) stopRecordingAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.WHITE)
        root.setPadding(48, 48, 48, 48)

        status = TextView(this)
        status.textSize = 16f
        status.setTextColor(Color.BLACK)
        status.gravity = Gravity.CENTER
        status.text = "اختبار الصفحة 3: سجّل صوتك أو اختر ملف صوت"
        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        recordButton = Button(this)
        recordButton.text = "تسجيل صوت جديد"
        recordButton.setOnClickListener { onRecordClicked() }
        val recordParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        recordParams.topMargin = 48
        root.addView(recordButton, recordParams)

        val pickButton = Button(this)
        pickButton.text = "اختيار ملف صوت موجود"
        pickButton.setOnClickListener { pickAudio() }
        val pickParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        pickParams.topMargin = 24
        root.addView(pickButton, pickParams)

        setContentView(root)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        releaseRecorder()
        super.onDestroy()
    }

    private fun onRecordClicked() {
        if (recording) {
            stopRecordingAndStart()
            return
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
            return
        }
        startRecording()
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        try {
            val file = File(filesDir, "page3_test_record.m4a")
            if (file.exists()) file.delete()

            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()

            recorder = r
            recordFile = file
            recording = true
            recordButton.text = "إيقاف التسجيل وبدء الرندر"
            status.text = "جاري التسجيل... احكِ جملة (حتى 20 ثانية)"
            handler.postDelayed(autoStop, maxRecordMillis)
        } catch (e: Throwable) {
            releaseRecorder()
            recording = false
            recordButton.text = "تسجيل صوت جديد"
            status.text = "فشل بدء التسجيل: ${e.message}"
        }
    }

    private fun stopRecordingAndStart() {
        handler.removeCallbacks(autoStop)
        val r = recorder
        recorder = null
        recording = false
        recordButton.text = "تسجيل صوت جديد"

        try {
            r?.stop()
        } catch (e: RuntimeException) {
            try { r?.release() } catch (ignored: Throwable) { }
            status.text = "التسجيل قصير جداً، حاول مرة ثانية"
            return
        }
        try { r?.release() } catch (ignored: Throwable) { }

        val file = recordFile
        if (file == null || !file.exists() || file.length() < 1000L) {
            status.text = "ملف التسجيل فارغ، حاول مرة ثانية"
            return
        }
        launchPage3(file)
    }

    private fun releaseRecorder() {
        val r = recorder ?: return
        recorder = null
        try { r.stop() } catch (ignored: Throwable) { }
        try { r.release() } catch (ignored: Throwable) { }
    }

    private fun launchPage3(file: File) {
        val character = Random().nextInt(15) + 1
        status.text = "الشخصية $character — جاري البدء"
        Page3Activity.start(this, character, file.absolutePath)
    }

    private fun pickAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "audio/*"
        startActivityForResult(intent, pickRequest)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val target = File(filesDir, "page3_test_input.audio")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            launchPage3(target)
        } catch (e: Throwable) {
            status.text = "فشل قراءة الملف: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != permissionRequest) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            status.text = "لازم تسمح بصلاحية الميكروفون لتسجيل الصوت"
        }
    }
}
