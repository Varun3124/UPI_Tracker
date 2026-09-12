package com.varun.upitracker.ui.backup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.varun.upitracker.R
import com.varun.upitracker.data.backup.DriveAuthResult
import com.varun.upitracker.data.backup.GoogleAccountRepository
import com.varun.upitracker.data.backup.RestoreReport
import com.varun.upitracker.data.backup.SignInResult
import com.varun.upitracker.domain.backup.BackupDecision
import com.varun.upitracker.domain.backup.LocalBackupState
import com.varun.upitracker.domain.backup.RemoteBackupInfo
import com.varun.upitracker.ui.dashboard.DashboardActivity
import com.varun.upitracker.ui.settings.AppViewModelFactory
import com.varun.upitracker.ui.theme.padRootForSystemBars
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Back up to, and restore from, the user's own Google Drive.
 *
 * This screen owns everything that needs an Activity: the sign-in sheet, the Drive consent screen,
 * and the dialogs that decide what happens when this phone and the backup disagree. The work itself
 * belongs to [BackupViewModel].
 *
 * The one rule the dialogs below encode: **restore is never the default and never automatic.** When
 * both sides hold real data the user is shown both, and keeping this phone is the emphasised choice.
 */
class BackupActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "BackupActivity"
    }

    private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    private lateinit var viewModel: BackupViewModel
    private lateinit var accounts: GoogleAccountRepository

    /** What to do once the user has finished with the Drive consent screen. */
    private var pendingAction: ((String) -> Unit)? = null

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val action = pendingAction
        pendingAction = null
        when (val authorized = accounts.resultFrom(this, result.data)) {
            is DriveAuthResult.Authorized -> action?.invoke(authorized.accessToken)
            is DriveAuthResult.Failed -> toast(authorized.reason)
            // Being asked to consent again after consenting would be a loop; stop and say so.
            is DriveAuthResult.NeedsConsent -> toast("Google Drive access was not granted.")
        }
    }

    /** Whatever the user decides, the restore already happened; carry on to the dashboard. */
    private val smsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { goToDashboard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        padRootForSystemBars(R.id.main)

        accounts = GoogleAccountRepository(applicationContext)
        viewModel = ViewModelProvider(this, AppViewModelFactory(this))[BackupViewModel::class.java]

        findViewById<ImageButton>(R.id.btnBackBackup).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAccount).setOnClickListener { onAccountTapped() }
        findViewById<Button>(R.id.btnBackUpNow).setOnClickListener { withDriveToken(::startUpload) }
        findViewById<Button>(R.id.btnRestore).setOnClickListener { withDriveToken(::startRestore) }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { confirmUndo() }

        viewModel.uiState.observe(this, ::render)
        viewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun render(state: BackupUiState) {
        findViewById<TextView>(R.id.tvAccount).text = state.accountEmail ?: "Not signed in"
        findViewById<Button>(R.id.btnAccount).text = if (state.isSignedIn) "Sign out" else "Sign in"

        findViewById<TextView>(R.id.tvLastBackup).text = state.lastBackupEpoch
            ?.let { "Last backed up ${dateFmt.format(Date(it))}" }
            ?: "Not backed up yet"
        findViewById<TextView>(R.id.tvLocalSummary).text = state.local?.let(::describe).orEmpty()

        findViewById<Button>(R.id.btnBackUpNow).isEnabled = state.isSignedIn && !state.isBusy
        findViewById<Button>(R.id.btnRestore).isEnabled = state.isSignedIn && !state.isBusy
        findViewById<Button>(R.id.btnAccount).isEnabled = !state.isBusy

        findViewById<View>(R.id.cardUndo).visibility =
            if (state.snapshot != null) View.VISIBLE else View.GONE
        state.snapshot?.let {
            findViewById<TextView>(R.id.tvUndo).text =
                "A copy of this phone from ${dateFmt.format(Date(it.takenAtEpoch))} was saved before " +
                    "the last restore. You can put it back."
        }
        findViewById<Button>(R.id.btnUndo).isEnabled = !state.isBusy

        findViewById<View>(R.id.busyBar).visibility = if (state.isBusy) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvBusy).text = state.busyMessage.orEmpty()
    }

    private fun describe(local: LocalBackupState): String = if (local.isUntouched) {
        "This phone has nothing recorded yet."
    } else {
        "This phone has ${local.transactionCount} transactions, ${local.accountCount} accounts " +
            "and ${local.friendCount} people."
    }

    // --- account ---------------------------------------------------------------------------------

    private fun onAccountTapped() {
        if (viewModel.uiState.value?.isSignedIn == true) {
            AlertDialog.Builder(this)
                .setTitle("Sign out?")
                .setMessage(
                    "Your transactions stay on this phone and the backup stays in Drive. You just " +
                        "won't be able to back up or restore until you sign in again."
                )
                .setPositiveButton("Sign out") { _, _ -> viewModel.signOut() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        lifecycleScope.launch {
            when (val result = accounts.signIn(this@BackupActivity)) {
                is SignInResult.Success -> viewModel.refresh()
                is SignInResult.Cancelled -> Unit // The user said no; saying it back is noise.
                is SignInResult.NoAccount ->
                    toast("There is no Google account on this phone. Add one in Android settings first.")
                is SignInResult.Failed -> toast(result.reason)
            }
        }
    }

    /**
     * Runs [action] with a Drive token, prompting for consent only if it is not already granted.
     *
     * Asked for fresh every time rather than cached: tokens last about an hour, and Play services
     * answers silently once consent stands, so there is nothing to gain by holding one.
     */
    private fun withDriveToken(action: (String) -> Unit) {
        lifecycleScope.launch {
            val result = accounts.authorizeDrive(this@BackupActivity)
            // One line per tap, so a flow that stops short says where it stopped. Logs the outcome's
            // kind only: the Authorized case carries a live access token that must not reach logcat.
            Log.d(TAG, "Drive authorization: ${result::class.simpleName}")
            when (result) {
                is DriveAuthResult.Authorized -> action(result.accessToken)
                is DriveAuthResult.NeedsConsent -> {
                    pendingAction = action
                    consentLauncher.launch(IntentSenderRequest.Builder(result.pendingIntent).build())
                }
                is DriveAuthResult.Failed -> toast(result.reason)
            }
        }
    }

    // --- backing up ------------------------------------------------------------------------------

    private fun startUpload(token: String) {
        viewModel.backUpNow(token, onOutcome = { outcome ->
            when (outcome) {
                is BackUpOutcome.Done ->
                    toast("Backed up ${outcome.summary.sizeBytes / 1024} KB to Google Drive.")
                is BackUpOutcome.NeedsDecision -> when (val decision = outcome.decision) {
                    is BackupDecision.UploadWouldOverwriteNewer -> confirmOverwrite(token, decision.remote)
                    is BackupDecision.Nothing -> toast(decision.reason)
                    else -> toast("Nothing to back up.")
                }
            }
        }, onError = ::toast)
    }

    /**
     * The backup in Drive is newer than anything this install has sent, so it came from somewhere
     * else. Replacing it is a real loss and has to be asked about.
     */
    private fun confirmOverwrite(token: String, remote: RemoteBackupInfo) {
        val local = viewModel.uiState.value?.local
        AlertDialog.Builder(this)
            .setTitle("Replace the backup in Drive?")
            .setMessage(
                buildString {
                    append("Drive already holds a newer backup, made ")
                    append(dateFmt.format(Date(remote.createdAtEpoch)))
                    append(" on another device, with ${remote.transactionCount} transactions.\n\n")
                    if (local != null && local.isUntouched) {
                        append("This phone has nothing recorded yet, so backing up now would replace ")
                        append("that backup with an empty one.")
                    } else if (local != null) {
                        append("This phone has ${local.transactionCount} transactions. ")
                        append("Backing up replaces what is in Drive.")
                    }
                }
            )
            // Keeping what is in Drive is the emphasised choice; replacing it is the deliberate one.
            .setPositiveButton("Keep the Drive backup", null)
            .setNegativeButton("Replace it") { _, _ -> doUpload(token) }
            .show()
    }

    private fun doUpload(token: String) {
        viewModel.upload(token, onDone = { summary ->
            toast("Backed up ${summary.sizeBytes / 1024} KB to Google Drive.")
        }, onError = ::toast)
    }

    // --- restoring -------------------------------------------------------------------------------

    private fun startRestore(token: String) {
        viewModel.planRestore(token, onDecision = { decision ->
            when (decision) {
                is BackupDecision.SafeRestore -> confirmRestore(token, decision.remote, replacing = null)
                is BackupDecision.RestoreWouldReplaceLocal ->
                    confirmRestore(token, decision.remote, replacing = decision.local)
                is BackupDecision.Incompatible -> AlertDialog.Builder(this)
                    .setTitle("Can't use that backup")
                    .setMessage(decision.reason)
                    .setPositiveButton("OK", null)
                    .show()
                is BackupDecision.Nothing -> toast(decision.reason)
                else -> toast("There is nothing to restore.")
            }
        }, onError = ::toast)
    }

    /**
     * [replacing] is non-null when this phone holds data of its own, which is the case that needs
     * care: both sides are shown, and keeping this phone is the emphasised button.
     */
    private fun confirmRestore(token: String, remote: RemoteBackupInfo, replacing: LocalBackupState?) {
        val message = buildString {
            append("The backup was made ${dateFmt.format(Date(remote.createdAtEpoch))} ")
            append("and holds ${remote.transactionCount} transactions ")
            append("across ${remote.accountCount} accounts.\n\n")
            if (replacing == null) {
                append("This phone has nothing recorded yet, so nothing here will be lost.")
            } else {
                append("This phone has ${replacing.transactionCount} transactions ")
                append("across ${replacing.accountCount} accounts. Restoring replaces all of it.\n\n")
                append("A copy of this phone is saved first, so you can undo this.")
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (replacing == null) "Restore this backup?" else "Replace everything on this phone?")
            .setMessage(message)

        if (replacing == null) {
            builder.setPositiveButton("Restore") { _, _ -> doRestore(token, remote) }
                .setNegativeButton("Cancel", null)
        } else {
            // Inverted on purpose: the safe choice gets the emphasis, and restore is never the
            // button a distracted thumb lands on.
            builder.setPositiveButton("Keep this phone's data", null)
                .setNegativeButton("Restore anyway") { _, _ -> doRestore(token, remote) }
        }
        builder.show()
    }

    private fun doRestore(token: String, remote: RemoteBackupInfo) {
        viewModel.restore(token, remote, onDone = { report ->
            announceAndRelaunch("Restored ${report.totalRows} rows.", report)
        }, onError = ::toast)
    }

    private fun confirmUndo() {
        val snapshot = viewModel.uiState.value?.snapshot ?: return
        AlertDialog.Builder(this)
            .setTitle("Undo the last restore?")
            .setMessage(
                "This puts back the copy of this phone saved on " +
                    "${dateFmt.format(Date(snapshot.takenAtEpoch))}, replacing what was restored."
            )
            .setPositiveButton("Put it back") { _, _ ->
                viewModel.undoLastRestore(onDone = { report ->
                    if (report == null) {
                        toast("There is nothing left to undo.")
                    } else {
                        announceAndRelaunch("Put back ${report.totalRows} rows.", report)
                    }
                }, onError = ::toast)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Restarts at the dashboard rather than returning to whatever was on screen.
     *
     * Screens and view models cache what they read -- `StatisticsViewModel` alone holds resolved
     * scopes, bucket flows and an account list -- and every one of those is about a database that no
     * longer exists. Clearing the task is cheaper and more honest than hunting each cache down.
     */
    private fun announceAndRelaunch(message: String, report: RestoreReport) {
        if (report.unknownTables.isNotEmpty() || report.droppedColumns.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Restored, with some parts skipped")
                .setMessage(
                    message + "\n\nThis backup was made by a different version of the app, so a few " +
                        "things in it no longer exist here and were left out."
                )
                .setPositiveButton("OK") { _, _ -> relaunch() }
                .setCancelable(false)
                .show()
        } else {
            toast(message)
            relaunch()
        }
    }

    /**
     * A restore can carry `onboarding_complete` onto a phone that has never been asked for SMS
     * access -- the backup holds the flag, but a runtime permission is not restorable state. Left
     * alone, the user would land on the dashboard with their history intact and nothing new ever
     * being captured, which is the kind of silence nobody notices for weeks.
     */
    private fun relaunch() {
        if (!isSmsGranted()) {
            AlertDialog.Builder(this)
                .setTitle("Allow SMS access?")
                .setMessage(
                    "Your data is back. To keep capturing new transactions, DhanMoney needs to read " +
                        "the alerts your bank sends by SMS."
                )
                .setPositiveButton("Allow") { _, _ ->
                    smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
                }
                .setNegativeButton("Not now") { _, _ -> goToDashboard() }
                .setCancelable(false)
                .show()
            return
        }
        goToDashboard()
    }

    private fun isSmsGranted(): Boolean =
        listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun goToDashboard() {
        startActivity(
            Intent(this, DashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
