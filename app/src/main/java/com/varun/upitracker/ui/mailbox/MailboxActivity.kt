package com.varun.upitracker.ui.mailbox

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.varun.upitracker.R
import com.varun.upitracker.data.backup.DriveAuthResult
import com.varun.upitracker.data.backup.GoogleAccountRepository
import com.varun.upitracker.data.backup.SignInResult
import com.varun.upitracker.data.mailbox.MailboxStatus
import com.varun.upitracker.data.mailbox.TurnOnResult
import com.varun.upitracker.domain.mailbox.KeyFingerprint
import com.varun.upitracker.ui.settings.AppViewModelFactory
import com.varun.upitracker.ui.theme.padRootForSystemBars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Turning the friends mailbox on and off, and deleting the account behind it.
 *
 * Owns everything that needs an Activity -- the Google sign-in sheet and the Drive consent screen --
 * exactly as [com.varun.upitracker.ui.backup.BackupActivity] does for backups. Drive is needed because
 * the private key's recoverable copy lives in the same private folder as the backup.
 */
class MailboxActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MailboxActivity"
    }

    private lateinit var viewModel: MailboxViewModel
    private lateinit var accounts: GoogleAccountRepository

    /**
     * Back from Google's Drive consent screen. What was waiting on it is read from the ViewModel, not
     * from this Activity, which may be a new one by now: while that screen is up, a phone short of
     * memory is free to destroy the screen underneath.
     */
    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.setStep(null)
        when (val authorized = accounts.resultFrom(this, result.data)) {
            is DriveAuthResult.Authorized -> {
                val resumed = viewModel.resume(
                    authorized.accessToken,
                    onTurnedOn = ::announceTurnedOn,
                    onDeleted = { toast("Your mailbox account has been deleted.") },
                    onError = ::toast
                )
                // The whole app was restarted behind the consent screen, sign-in and all. Drive
                // access is granted now, so a second tap goes straight through.
                if (!resumed) toast("Google Drive access is on. Tap the button again to finish.")
            }
            is DriveAuthResult.Failed -> {
                viewModel.pending = null
                toast(authorized.reason)
            }
            // Being asked to consent again after consenting would be a loop; stop and say so.
            is DriveAuthResult.NeedsConsent -> {
                viewModel.pending = null
                toast("Google Drive access was not granted.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mailbox)
        padRootForSystemBars(R.id.main)

        accounts = GoogleAccountRepository(applicationContext)
        viewModel = ViewModelProvider(this, AppViewModelFactory(this))[MailboxViewModel::class.java]

        findViewById<ImageButton>(R.id.btnBackMailbox).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnMailboxToggle).setOnClickListener { onToggle() }
        findViewById<Button>(R.id.btnDeleteMailbox).setOnClickListener { confirmDelete() }

        viewModel.uiState.observe(this, ::render)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun render(state: MailboxUiState) {
        val stateLine = findViewById<TextView>(R.id.tvMailboxState)
        val detail = findViewById<TextView>(R.id.tvMailboxDetail)
        val toggle = findViewById<Button>(R.id.btnMailboxToggle)
        val delete = findViewById<Button>(R.id.btnDeleteMailbox)

        when (val status = state.status) {
            null -> {
                stateLine.text = "Checking…"
                detail.text = ""
                toggle.visibility = View.GONE
                delete.visibility = View.GONE
            }
            MailboxStatus.NotConfigured -> {
                stateLine.text = "Not available in this version"
                detail.text = "This copy of DhanMoney was built without a mailbox server, so it cannot send " +
                    "or receive. Sharing a parcel by pasting it into a chat still works."
                toggle.visibility = View.GONE
                delete.visibility = View.GONE
            }
            MailboxStatus.Off -> {
                stateLine.text = "Off"
                detail.text = "Turn it on to link with friends and send transactions straight to their app. " +
                    "You will be asked to sign in with Google and to allow a private folder in your Drive."
                toggle.visibility = View.VISIBLE
                toggle.text = "Turn on"
                delete.visibility = View.GONE
            }
            is MailboxStatus.NeedsKeys -> {
                stateLine.text = "Signed in as ${status.email}"
                detail.text = "This phone does not have your mailbox key. Turn the mailbox on again to fetch " +
                    "it from your Google Drive."
                toggle.visibility = View.VISIBLE
                toggle.text = "Turn on"
                delete.visibility = View.VISIBLE
            }
            is MailboxStatus.On -> {
                stateLine.text = "On for ${status.email}"
                detail.text = "Your security key: ${KeyFingerprint.display(status.fingerprint)}\n" +
                    "A friend can compare this with what their app shows for you."
                toggle.visibility = View.VISIBLE
                toggle.text = "Turn off"
                delete.visibility = View.VISIBLE
            }
        }

        toggle.isEnabled = !state.showsProgress
        delete.isEnabled = !state.showsProgress
        findViewById<View>(R.id.busyBar).visibility = if (state.showsProgress) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvBusy).text = (state.busyMessage ?: state.step).orEmpty()
    }

    private fun onToggle() {
        if (viewModel.uiState.value?.status is MailboxStatus.On) {
            AlertDialog.Builder(this)
                .setTitle("Turn off the friends mailbox?")
                .setMessage(
                    "Nothing is deleted. Your links, what friends already sent and the copy of your key in " +
                        "Google Drive all stay, and you can turn it back on at any time. While it is off, " +
                        "nothing new arrives on this phone."
                )
                .setPositiveButton("Turn off") { _, _ -> viewModel.turnOff(::toast) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        withGoogle(MailboxAction.TURN_ON)
    }

    private fun announceTurnedOn(result: TurnOnResult) {
        val notes = buildList {
            if (result.removedOtherAccountsLinks) {
                add(
                    "Links made while a different Google account was signed in have been removed. Link " +
                        "with those friends again from their pages."
                )
            }
            if (result.replacedPublishedKey) {
                add(
                    "Your previous key could not be found, so a new one was made. Friends who linked with " +
                        "you before need to link with you again."
                )
            }
        }
        if (notes.isEmpty()) {
            toast("The friends mailbox is on.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("The friends mailbox is on")
            .setMessage(notes.joinToString("\n\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Deleting asks for the sign-in again, which Firebase needs anyway, and doubles as the "is this
     * really you" check. Keeping the account is the emphasised choice.
     */
    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete your mailbox account?")
            .setMessage(
                "This removes you from the friends mailbox for good: your published key, anything waiting " +
                    "for you, your links to friends, and the copy of your key in Google Drive. Messages you " +
                    "sent that nobody has collected yet are taken back.\n\n" +
                    "Your transactions stay exactly as they are. You will be asked to sign in once more."
            )
            .setPositiveButton("Keep my account", null)
            .setNegativeButton("Delete account") { _, _ -> withGoogle(MailboxAction.DELETE) }
            .show()
    }

    /**
     * Signs in, then gets Drive access, then does [action] -- saying which step is under way, since
     * each Google step can take a few seconds with nothing else on screen.
     */
    private fun withGoogle(action: MailboxAction) {
        viewModel.setStep("Signing in with Google…")
        lifecycleScope.launch {
            // Cleared however it ends, a screen torn down mid-sheet included, so the buttons come back.
            val result = try {
                accounts.signIn(this@MailboxActivity)
            } finally {
                viewModel.setStep(null)
            }
            when (result) {
                is SignInResult.Success -> {
                    viewModel.pending = PendingMailboxStep(
                        action, result.idToken, result.account.email, result.account.displayName
                    )
                    continueWithDrive()
                }
                is SignInResult.Cancelled -> Unit // The user said no; saying it back is noise.
                is SignInResult.NoAccount ->
                    toast("There is no Google account on this phone. Add one in Android settings first.")
                is SignInResult.Failed -> toast(result.reason)
            }
        }
    }

    /** Gets a Drive token for what [MailboxViewModel.pending] holds, prompting for consent only if it is not already granted. */
    private fun continueWithDrive() {
        viewModel.setStep("Getting access to your Google Drive…")
        lifecycleScope.launch {
            val result = try {
                accounts.authorizeDrive(this@MailboxActivity)
            } catch (error: CancellationException) {
                viewModel.setStep(null)
                throw error
            }
            // The outcome's kind only: the Authorized case carries a live access token.
            Log.d(TAG, "Drive authorization: ${result::class.simpleName}")
            when (result) {
                is DriveAuthResult.Authorized -> {
                    viewModel.setStep(null)
                    viewModel.resume(result.accessToken, ::announceTurnedOn, { toast("Your mailbox account has been deleted.") }, ::toast)
                }
                // The step stays up until the consent screen answers.
                is DriveAuthResult.NeedsConsent ->
                    consentLauncher.launch(IntentSenderRequest.Builder(result.pendingIntent).build())
                is DriveAuthResult.Failed -> {
                    viewModel.setStep(null)
                    viewModel.pending = null
                    toast(result.reason)
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
