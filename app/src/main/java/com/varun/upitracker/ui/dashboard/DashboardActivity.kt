package com.varun.upitracker.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.varun.upitracker.R
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.ledger.FriendLedgerSummary
import com.varun.upitracker.ui.AllTransactionsActivity
import com.varun.upitracker.ui.AmountPerspective
import com.varun.upitracker.ui.FriendDetailActivity
import com.varun.upitracker.ui.LedgerEntry
import com.varun.upitracker.ui.color
import com.varun.upitracker.ui.formatTransferAmount
import com.varun.upitracker.ui.perspectiveColor
import com.varun.upitracker.ui.settings.SettingsActivity
import com.varun.upitracker.ui.statistics.StatisticsActivity
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import com.varun.upitracker.ui.formatPerspectiveAmount
import com.varun.upitracker.ui.resolvePrimaryDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.varun.upitracker.util.AmountFormat
import android.widget.ImageButton
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.ui.theme.Avatars
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.ui.theme.dp
import com.varun.upitracker.ui.theme.padRootForSystemBars

class DashboardActivity : AppCompatActivity() {

    private lateinit var tvDailySpend: TextView
    private lateinit var tvWeeklySpend: TextView
    private lateinit var tvMonthlySpend: TextView
    private lateinit var recentRow: LinearLayout
    private lateinit var iouContainer: LinearLayout
    private lateinit var btnToggleInsignificantIou: TextView
    private val dateFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
    private lateinit var viewModel: DashboardViewModel

    /** IOUs this small are noise (loose change, rounding) - hidden by default. */
    private var showInsignificantIou = false
    private var latestIouSummaries: List<FriendLedgerSummary> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        padRootForSystemBars(R.id.main)

        tvDailySpend = findViewById(R.id.tvDailySpend)
        tvWeeklySpend = findViewById(R.id.tvWeeklySpend)
        tvMonthlySpend = findViewById(R.id.tvMonthlySpend)
        recentRow = findViewById(R.id.recentTransactionsRow)
        iouContainer = findViewById(R.id.iouContainer)
        btnToggleInsignificantIou = findViewById(R.id.btnToggleInsignificantIou)
        btnToggleInsignificantIou.setOnClickListener {
            showInsignificantIou = !showInsignificantIou
            buildIouSection(latestIouSummaries)
        }
        viewModel = ViewModelProvider(
            this,
            DashboardViewModelFactory(applicationContext)
        )[DashboardViewModel::class.java]

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.cardSpending).setOnClickListener {
            startActivity(Intent(this, StatisticsActivity::class.java))
        }

        findViewById<Button>(R.id.btnAddManual).setOnClickListener { launchManualEntry() }
        viewModel.uiState.observe(this) { state ->
            tvDailySpend.text = AmountFormat.rupees(state.dailySpendPaise)
            tvWeeklySpend.text = AmountFormat.rupees(state.weeklySpendPaise)
            tvMonthlySpend.text = AmountFormat.rupees(state.monthlySpendPaise)
            buildRecentRow(state.recentEntries)
            latestIouSummaries = state.iouSummaries
            buildIouSection(latestIouSummaries)
        }
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
        viewModel.scanSmsBacklog()
    }

    private fun loadData() {
        viewModel.loadData()
    }

    private fun buildRecentRow(entries: List<LedgerEntry>) {
        val db = AppDatabase.getInstance(applicationContext)
        recentRow.removeAllViews()
        entries.forEach { entry ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_transaction_card, recentRow, false)
            val payeeTv = card.findViewById<TextView>(R.id.tvCardPayee)
            val amountTv = card.findViewById<TextView>(R.id.tvCardAmount)
            val badge = card.findViewById<TextView>(R.id.tvPendingBadge)
            card.findViewById<TextView>(R.id.tvCardDate).text = dateFmt.format(Date(entry.dateEpoch))

            when (entry) {
                is LedgerEntry.Tx -> {
                    val tx = entry.transaction
                    lifecycleScope.launch {
                        payeeTv.text = withContext(Dispatchers.IO) { tx.resolvePrimaryDisplay(db) }
                    }
                    amountTv.text = tx.formatPerspectiveAmount()
                    amountTv.setTextColor(tx.perspectiveColor(this@DashboardActivity))
                    badge.visibility = if (tx.isPending) View.VISIBLE else View.GONE
                    card.setOnClickListener { openTransactionEntry(tx.id) }
                }

                is LedgerEntry.Transfer -> {
                    val transfer = entry.transfer
                    payeeTv.text = transfer.resolvePrimaryDisplay()
                    amountTv.text = transfer.formatTransferAmount()
                    amountTv.setTextColor(AmountPerspective.NEUTRAL.color(this@DashboardActivity))
                    badge.visibility = View.GONE
                    card.setOnClickListener { openTransferEntry(transfer.id) }
                }
            }
            recentRow.addView(card)
        }

        val viewAll = LayoutInflater.from(this).inflate(R.layout.item_transaction_card, recentRow, false)
        viewAll.findViewById<TextView>(R.id.tvCardPayee).text = "View all"
        viewAll.findViewById<TextView>(R.id.tvCardAmount).apply {
            text = "All"
            setTextColor(themeColor(ThemeAttr.primary))
        }
        viewAll.findViewById<TextView>(R.id.tvCardDate).text = "This month"
        viewAll.setOnClickListener { startActivity(Intent(this, AllTransactionsActivity::class.java)) }
        recentRow.addView(viewAll)
    }

    private fun buildIouSection(summaries: List<FriendLedgerSummary>) {
        iouContainer.removeAllViews()
        if (summaries.isEmpty()) {
            iouContainer.addView(TextView(this).apply {
                text = "No IOU records yet"
                textSize = 13f
                setTextColor(themeColor(ThemeAttr.textMuted))
                setPadding(0, dp(8), 0, dp(8))
            })
            btnToggleInsignificantIou.visibility = View.GONE
            return
        }

        val insignificantCount = summaries.count { isInsignificantIou(it) }
        val visibleSummaries = if (showInsignificantIou) {
            summaries
        } else {
            summaries.filterNot { isInsignificantIou(it) }
        }

        btnToggleInsignificantIou.visibility = if (insignificantCount > 0) View.VISIBLE else View.GONE
        btnToggleInsignificantIou.text = if (showInsignificantIou) {
            "Hide insignificant"
        } else {
            "Show insignificant ($insignificantCount)"
        }

        if (visibleSummaries.isEmpty()) {
            iouContainer.addView(TextView(this).apply {
                text = "No significant IOUs"
                textSize = 13f
                setTextColor(themeColor(ThemeAttr.textMuted))
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        visibleSummaries.forEach { summary ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_friend_iou, iouContainer, false)
            val initials = card.findViewById<TextView>(R.id.tvFriendInitials)
            val name = card.findViewById<TextView>(R.id.tvFriendName)
            val label = card.findViewById<TextView>(R.id.tvIouLabel)
            val amount = card.findViewById<TextView>(R.id.tvIouAmount)
            name.text = summary.friendName
            Avatars.bind(initials, summary.friendName, ActorType.FRIEND, fallback = "F")
            when {
                summary.netBalancePaise > 0 -> {
                    label.text = "owes you"
                    amount.text = AmountFormat.rupees(summary.netBalancePaise)
                    amount.setTextColor(themeColor(ThemeAttr.positive))
                }
                summary.netBalancePaise < 0 -> {
                    label.text = "you owe"
                    amount.text = AmountFormat.rupees(-summary.netBalancePaise)
                    amount.setTextColor(themeColor(ThemeAttr.negative))
                }
                else -> {
                    label.text = "settled"
                    amount.text = AmountFormat.rupees(0L)
                    amount.setTextColor(themeColor(ThemeAttr.amountNeutral))
                }
            }
            card.setOnClickListener {
                startActivity(Intent(this, FriendDetailActivity::class.java).apply {
                    putExtra(FriendDetailActivity.Companion.EXTRA_FRIEND_ID, summary.friendId)
                })
            }
            iouContainer.addView(card)
        }
    }

    private fun isInsignificantIou(summary: FriendLedgerSummary): Boolean =
        kotlin.math.abs(summary.netBalancePaise) < INSIGNIFICANT_IOU_THRESHOLD_PAISE

    private fun launchManualEntry() {
        startActivity(Intent(this, TransactionEntryActivity::class.java))
    }

    private fun openTransactionEntry(transactionId: Long) {
        startActivity(Intent(this, TransactionEntryActivity::class.java).apply {
            putExtra(TransactionEntryActivity.Companion.EXTRA_TRANSACTION_ID, transactionId)
        })
    }

    private fun openTransferEntry(transferId: String) {
        startActivity(Intent(this, TransactionEntryActivity::class.java).apply {
            putExtra(TransactionEntryActivity.Companion.EXTRA_TRANSFER_ID, transferId)
        })
    }

    private companion object {
        /** Rs100, below which an IOU balance is treated as noise and hidden by default. */
        const val INSIGNIFICANT_IOU_THRESHOLD_PAISE = 10_000L
    }
}
