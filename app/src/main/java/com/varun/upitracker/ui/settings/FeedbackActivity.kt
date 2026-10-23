package com.varun.upitracker.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.varun.upitracker.R
import com.varun.upitracker.ui.theme.padRootForSystemBars

/** "Improve app": a suggestion typed here goes to [FeedbackEmail.RECIPIENT] by the user's mail app. */
class FeedbackActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback)
        padRootForSystemBars(R.id.main)

        findViewById<ImageButton>(R.id.btnBackFeedback).setOnClickListener { finish() }
        val etSuggestion = findViewById<EditText>(R.id.etSuggestion)
        val btnSend = findViewById<Button>(R.id.btnSendFeedback)
        btnSend.isEnabled = FeedbackEmail.canSend(etSuggestion.text.toString())
        etSuggestion.doAfterTextChanged { btnSend.isEnabled = FeedbackEmail.canSend(it?.toString().orEmpty()) }
        btnSend.setOnClickListener { send(etSuggestion.text.toString()) }
    }

    /**
     * The page stays open with the text in it, since the mail app may be backed out of unsent.
     *
     * The recipient, subject and body go in both the mailto URI and the extras: mail apps differ
     * on which of the two they read for ACTION_SENDTO. Starting the intent and catching the miss,
     * rather than asking resolveActivity first, needs no `<queries>` entry on Android 11+.
     */
    private fun send(suggestion: String) {
        if (!FeedbackEmail.canSend(suggestion)) return
        val body = FeedbackEmail.body(
            suggestion = suggestion,
            appVersion = appVersion(),
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        val uri = Uri.parse(
            "mailto:${FeedbackEmail.RECIPIENT}" +
                "?subject=${Uri.encode(FeedbackEmail.SUBJECT)}" +
                "&body=${Uri.encode(body)}"
        )
        val intent = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(FeedbackEmail.RECIPIENT))
            .putExtra(Intent.EXTRA_SUBJECT, FeedbackEmail.SUBJECT)
            .putExtra(Intent.EXTRA_TEXT, body)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No mail app found. Write to ${FeedbackEmail.RECIPIENT} instead.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun appVersion(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")
}
