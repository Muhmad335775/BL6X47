package com.voicereact.app

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ConsentActivity : Activity() {

    companion object {
        private const val PREFS = "consent_prefs"
        private const val KEY_ACCEPTED = "terms_accepted"

        fun hasAccepted(context: android.content.Context): Boolean {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            return prefs.getBoolean(KEY_ACCEPTED, false)
        }

        private fun setAccepted(context: android.content.Context) {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ACCEPTED, true).apply()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(24), dp(48), dp(24), dp(24))

        val title = TextView(this)
        title.text = "Terms of Use"
        title.textSize = 20f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        column.addView(title)

        val spacer = android.view.View(this)
        column.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))

        val body = TextView(this)
        body.text = "This app is free and works fully offline.\n" +
            "You are solely and fully legally responsible for all your actions and content; the developer bears no responsibility for any misuse, damages, or legal consequences."
        body.textSize = 15f
        body.setLineSpacing(dp(4).toFloat(), 1f)

        val scroll = ScrollView(this)
        scroll.addView(body)
        column.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val checkRow = LinearLayout(this)
        checkRow.orientation = LinearLayout.HORIZONTAL
        checkRow.gravity = Gravity.CENTER_VERTICAL
        checkRow.setPadding(0, dp(20), 0, dp(12))

        val checkBox = CheckBox(this)
        checkRow.addView(checkBox)

        val checkLabel = TextView(this)
        checkLabel.text = "I have read and agree to the terms above"
        checkLabel.textSize = 14f
        checkRow.addView(checkLabel)

        column.addView(checkRow)

        val agreeButton = Button(this)
        agreeButton.text = "Agree"
        agreeButton.isEnabled = false
        agreeButton.setOnClickListener {
            setAccepted(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        column.addView(
            agreeButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        checkBox.setOnCheckedChangeListener { _, checked ->
            agreeButton.isEnabled = checked
        }

        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun onBackPressed() {
        // Consent cannot be dismissed by back button — app stays locked until accepted.
    }
}
