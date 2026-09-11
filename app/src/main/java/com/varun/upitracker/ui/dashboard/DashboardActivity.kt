package com.varun.upitracker.ui.dashboard

import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.PaintDrawable
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
    private lateinit var cardSpendingGradient: View
    private lateinit var dividerCashFlow1: View
    private lateinit var dividerCashFlow2: View
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
        cardSpendingGradient = findViewById(R.id.cardSpendingGradient)
        dividerCashFlow1 = findViewById(R.id.dividerCashFlow1)
        dividerCashFlow2 = findViewById(R.id.dividerCashFlow2)
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
            // Sign is carried by color alone here (see styleCashFlowCard), so the figure itself
            // never needs a minus sign.
            tvDailySpend.text = AmountFormat.rupees(kotlin.math.abs(state.dailySpendPaise))
            tvWeeklySpend.text = AmountFormat.rupees(kotlin.math.abs(state.weeklySpendPaise))
            tvMonthlySpend.text = AmountFormat.rupees(kotlin.math.abs(state.monthlySpendPaise))
            styleCashFlowCard(state.dailySpendPaise, state.weeklySpendPaise, state.monthlySpendPaise)
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

    /**
     * Tints each segment by its own sign -- the card's usual accent for a net outflow, green for a
     * net inflow -- and paints the card as one gradient across the three rather than three flat
     * blocks, with the color changing right where the segment itself does.
     */
    private fun styleCashFlowCard(dailyPaise: Long, weeklyPaise: Long, monthlyPaise: Long) {
        val (dailyBg, dailyFg) = cashFlowColors(dailyPaise)
        val (weeklyBg, weeklyFg) = cashFlowColors(weeklyPaise)
        val (monthlyBg, monthlyFg) = cashFlowColors(monthlyPaise)

        tvDailySpend.setTextColor(dailyFg)
        tvWeeklySpend.setTextColor(weeklyFg)
        tvMonthlySpend.setTextColor(monthlyFg)

        // The dividers' positions are only meaningful once the row has been laid out. Posting
        // defers this to right after that, which by the time this runs has always already
        // happened.
        cardSpendingGradient.post {
            cardSpendingGradient.background = buildCashFlowGradient(dailyBg, weeklyBg, monthlyBg)
        }
    }

    /**
     * A left-to-right gradient whose two blends sit exactly on [dividerCashFlow1] and
     * [dividerCashFlow2] -- the same rules the Today / This week / This month columns are split
     * by -- each [BORDER_BLEND_DP] wide, rather than spread evenly across the whole card. That
     * makes the color change read as a steep edge right at the segment boundary instead of a slow
     * wash from one third of the card to the next.
     */
    private fun buildCashFlowGradient(dailyBg: Int, weeklyBg: Int, monthlyBg: Int): Drawable {
        val card = cardSpendingGradient.parent as View
        val width = cardSpendingGradient.width.toFloat()
        val border1 = dividerCashFlow1.centerXRelativeTo(card) / width
        val border2 = dividerCashFlow2.centerXRelativeTo(card) / width
        val halfBlend = dp(BORDER_BLEND_DP) / width

        return PaintDrawable().apply {
            setCornerRadius(dp(16).toFloat())
            paint.shader = LinearGradient(
                0f, 0f, width, 0f,
                intArrayOf(dailyBg, dailyBg, weeklyBg, weeklyBg, monthlyBg, monthlyBg),
                floatArrayOf(
                    0f,
                    border1 - halfBlend, border1 + halfBlend,
                    border2 - halfBlend, border2 + halfBlend,
                    1f
                ),
                Shader.TileMode.CLAMP
            )
        }
    }

    /** This view's horizontal center, in [ancestor]'s coordinate space. */
    private fun View.centerXRelativeTo(ancestor: View): Float {
        var x = width / 2f
        var v: View = this
        while (v !== ancestor) {
            x += v.left
            v = v.parent as View
        }
        return x
    }

    /** A net inflow (negative net spend) reads as a gain, so it borrows the app's green -- the same
     *  role [FriendLedgerSummary] uses for "owes you". A net outflow keeps the card's usual accent. */
    private fun cashFlowColors(netPaise: Long): Pair<Int, Int> = if (netPaise < 0) {
        themeColor(ThemeAttr.positiveContainer) to themeColor(ThemeAttr.onPositiveContainer)
    } else {
        themeColor(ThemeAttr.primaryContainer) to themeColor(ThemeAttr.onPrimaryContainer)
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

        /** Half-width, each side of a segment border, of the cash flow card's color blend. */
        const val BORDER_BLEND_DP = 10
    }
}
