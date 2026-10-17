package com.varun.upitracker.ui.share

import android.view.LayoutInflater
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.varun.upitracker.R
import com.varun.upitracker.data.prefs.SelfIdentity

/**
 * Asks for the alias and UPI ID outside onboarding: from Settings, and the first time someone who
 * onboarded before either existed shares a parcel. [onSaved] runs only once both are stored.
 */
object SelfIdentityDialog {

    fun show(
        activity: AppCompatActivity,
        title: String = "How friends see you",
        suggestedAlias: String? = null,
        onSaved: () -> Unit = {}
    ) {
        val identity = SelfIdentity(activity)
        val fields = LayoutInflater.from(activity).inflate(R.layout.view_self_identity_fields, null)
        val etAlias = fields.findViewById<EditText>(R.id.etSelfAlias)
        val etUpi = fields.findViewById<EditText>(R.id.etSelfUpiId)
        etAlias.setText(identity.alias() ?: suggestedAlias.orEmpty())
        etUpi.setText(identity.upiId().orEmpty())

        val gutter = activity.resources.getDimensionPixelSize(R.dimen.space_xl)
        val container = FrameLayout(activity).apply {
            setPadding(gutter, gutter / 2, gutter, 0)
            addView(fields)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        // Wired after show so a refusal keeps the dialog open with what was typed.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (val result = identity.set(etAlias.text.toString(), etUpi.text.toString())) {
                    is SelfIdentity.Result.Invalid ->
                        Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                    SelfIdentity.Result.Saved -> {
                        dialog.dismiss()
                        onSaved()
                    }
                }
            }
        }
        dialog.show()
    }
}
