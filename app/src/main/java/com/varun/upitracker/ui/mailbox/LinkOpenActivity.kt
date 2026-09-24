package com.varun.upitracker.ui.mailbox

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.varun.upitracker.domain.mailbox.InviteCode
import com.varun.upitracker.ui.parcel.ParcelImportActivity

/**
 * Opens an invite a friend sent as a link, so that tapping it in a chat does what pasting the code
 * does.
 *
 * The one exported activity in the app, because only an exported one can answer a tap in another
 * app. It is a doorway and nothing else: it reads the code out of the link, hands it to
 * [ParcelImportActivity] and finishes. Nothing is written, nothing is sent, and the user still says
 * which of their people the invite is from -- so an app firing this intent by itself achieves no
 * more than typing the same code into the box would.
 */
class LinkOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val code = intent?.data?.toString()?.let(InviteCode::codeFrom)
        if (code == null) {
            Toast.makeText(this, "That link does not carry an invite. Ask for it to be sent again.", Toast.LENGTH_LONG)
                .show()
        }
        startActivity(
            Intent(this, ParcelImportActivity::class.java)
                .putExtra(ParcelImportActivity.EXTRA_INVITE_CODE, code)
        )
        finish()
    }
}
