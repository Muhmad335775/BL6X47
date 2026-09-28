package com.voicereact.app.page3

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Random

class Page3TestLauncherActivity : Activity() {

    private val pickRequest = 4711
    private lateinit var status: TextView

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
        status.text = "اختبار الصفحة 3: اختر أي ملف صوت (m4a / mp3 / wav)"
        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val button = Button(this)
        button.text = "اختيار ملف صوت وبدء الرندر"
        button.setOnClickListener { pickAudio() }
        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        buttonParams.topMargin = 48
        root.addView(button, buttonParams)

        setContentView(root)
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
            val character = Random().nextInt(15) + 1
            status.text = "الشخصية $character — جاري البدء"
            Page3Activity.start(this, character, target.absolutePath)
        } catch (e: Throwable) {
            status.text = "فشل قراءة الملف: ${e.message}"
        }
    }
}
