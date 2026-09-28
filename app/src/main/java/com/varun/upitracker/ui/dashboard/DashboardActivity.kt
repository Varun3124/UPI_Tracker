package com.varun.upitracker.ui.dashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.varun.upitracker.R
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.ledger.FriendLedgerSummary
import com.varun.upitracker.ui.AllTransactionsActivity
import com.varun.upitracker.ui.AmountPerspective
import com.varun.upitracker.ui.FriendDetailActivity
import com.varun.upitracker.ui.LedgerEntry
import com.varun.upitracker.ui.mailbox.MailboxInboxActivity
import com.varun.upitracker.ui.color
import com.varun.upitracker.ui.formatTransferAmount
import com.varun.upitracker.ui.colorAttr
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
import com.varun.upitracker.ui.chapter.ChapterDetailActivity
import com.varun.upitracker.domain.statistics.PerDayRate
import com.varun.upitracker.ui.chapter.ChaptersActivity
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.ui.theme.dp
import com.varun.upitracker.ui.theme.padRootForSystemBars

class DashboardActivity : AppCompatActivity() {

    private lateinit var tvDailySpend: TextView
    private lateinit var tvWeeklySpend: TextView
    private lateinit var tvMonthlySpend: TextView
    private lateinit var tvWeeklyPerDay: TextView
    private lateinit var tvMonthlyPerDay: TextView
    private lateinit var cardSpendingGradient: View
    private lateinit var dividerCashFlow1: View
    private lateinit var dividerCashFlow2: View
    private lateinit var recentRow: LinearLayout
    private lateinit var iouContainer: LinearLayout
    private lateinit var iouTabRow: LinearLayout
    private lateinit var btnToggleInsignificantIou: TextView
    private val dateFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
    private lateinit var viewModel: DashboardViewModel

    /** Whether this screen has already asked for SMS access; see [onResume]. */
    private var askedForSms = false

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.READ_SMS] == true) {
            viewModel.scanSmsBacklog()
            loadData()
        }
    }

    /** IOUs this small are noise (loose change, rounding) - hidden by default. */
    private var showInsignificantIou = false
    private var latestIouSummaries: List<FriendLedgerSummary> = emptyList()

    /**
     * Which heading of the IOU slider is chosen: null for IOU itself, else a chapter's id.
     *
     * Held by the screen rather than the ViewModel because nothing loaded depends on it -- every
     * open chapter's plan arrives with the rest of the state, so switching tabs is a redraw.
     */
    private var selectedChapterId: Long? = null
    private var latestChapters: List<DashboardChapter> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        padRootForSystemBars(R.id.main)

        tvDailySpend = findViewById(R.id.tvDailySpend)
        tvWeeklySpend = findViewById(R.id.tvWeeklySpend)
        tvMonthlySpend = findViewById(R.id.tvMonthlySpend)
        tvWeeklyPerDay = findViewById(R.id.tvWeeklyPerDay)
        tvMonthlyPerDay = findViewById(R.id.tvMonthlyPerDay)
        cardSpendingGradient = findViewById(R.id.cardSpendingGradient)
        dividerCashFlow1 = findViewById(R.id.dividerCashFlow1)
        dividerCashFlow2 = findViewById(R.id.dividerCashFlow2)
        recentRow = findViewById(R.id.recentTransactionsRow)
        iouContainer = findViewById(R.id.iouContainer)
        iouTabRow = findViewById(R.id.iouTabRow)
        findViewById<View>(R.id.btnOpenChapters).setOnClickListener { openChapters() }
        btnToggleInsignificantIou = findViewById(R.id.btnToggleInsignificantIou)
        btnToggleInsignificantIou.setOnClickListener {
            showInsignificantIou = !showInsignificantIou
            renderIouBody()
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
        findViewById<View>(R.id.btnMailboxBell).setOnClickListener {
            startActivity(Intent(this, MailboxInboxActivity::class.java))
        }

        findViewById<Button>(R.id.btnAddManual).setOnClickListener { launchManualEntry() }
        viewModel.uiState.observe(this) { state ->
            // Sign is carried by color alone here (see styleCashFlowCard), so the figure itself
            // never needs a minus sign.
            tvDailySpend.text = AmountFormat.rupees(kotlin.math.abs(state.dailySpendPaise))
            tvWeeklySpend.text = AmountFormat.rupees(kotlin.math.abs(state.weeklySpendPaise))
            tvMonthlySpend.text = AmountFormat.rupees(kotlin.math.abs(state.monthlySpendPaise))
            renderPerDay(tvWeeklyPerDay, state.weeklySpendPaise, state.weeklyDays)
            renderPerDay(tvMonthlyPerDay, state.monthlySpendPaise, state.monthlyDays)
            styleCashFlowCard(state.dailySpendPaise, state.weeklySpendPaise, state.monthlySpendPaise)
            buildRecentRow(state.recentEntries)
            latestIouSummaries = state.iouSummaries
            latestChapters = state.chapters
            renderIouSlider()
            renderMailboxBell(state.mailboxOn, state.mailboxWaiting)
        }
        loadData()
    }

    /**
     * The bell stands for the friends mailbox as a whole, so it stays there whenever the mailbox is
     * on -- the user can open it to check. The badge is only for what is already waiting.
     */
    private fun renderMailboxBell(mailboxOn: Boolean, waiting: Int) {
        findViewById<View>(R.id.mailboxBell).visibility = if (mailboxOn) View.VISIBLE else View.GONE
        val badge = findViewById<TextView>(R.id.tvMailboxBellBadge)
        badge.visibility = if (waiting > 0) View.VISIBLE else View.GONE
        badge.text = if (waiting > 99) "99+" else waiting.toString()
        findViewById<View>(R.id.btnMailboxBell).contentDescription = when {
            waiting == 0 -> getString(R.string.cd_from_friends)
            waiting == 1 -> "From friends, 1 thing waiting"
            else -> "From friends, $waiting things waiting"
        }
    }

    override fun onResume() {
        super.onResume()
        loadData()
        viewModel.collectMailbox()
        if (hasSmsPermission()) {
            viewModel.scanSmsBacklog()
        } else if (!askedForSms) {
            // Reaching the dashboard does not mean SMS access was ever granted: Android's backup can
            // restore this app's data -- onboarding flag included -- onto a phone that never saw the
            // onboarding permission screen, and the user can revoke access in settings at any time.
            // Asked once per visit: the permission dialog itself pauses and resumes this screen, so
            // without the flag a refusal would bring the prompt straight back, forever.
            askedForSms = true
            smsPermissionLauncher.launch(
                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            )
        }
    }

    private fun hasSmsPermission(): Boolean =
        listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun loadData() {
        viewModel.loadData()
    }

    /**
     * The rate beside a period figure, hidden while there is no rate worth showing.
     *
     * Unsigned, like the figure above it: this card carries the sign in colour alone -- see
     * [styleCashFlowCard] -- so a minus here would be the only one on it.
     */
    private fun renderPerDay(view: TextView, paise: Long, days: Int) {
        val perDay = PerDayRate.perDayPaise(kotlin.math.abs(paise), days)
        view.visibility = if (perDay == null) View.GONE else View.VISIBLE
        view.text = perDay?.let { "${AmountFormat.rupees(it)}/day" }.orEmpty()
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

    private fun openChapters() {
        startActivity(Intent(this, ChaptersActivity::class.java))
    }

    /**
     * The IOU heading and one heading per open chapter, plus whichever body the chosen one wants.
     *
     * A chapter that has since been closed or deleted -- this screen reloads on every resume -- can
     * still be the chosen one, so the choice is validated against the fresh list before anything is
     * drawn rather than left to produce an empty body.
     */
    private fun renderIouSlider() {
        if (latestChapters.none { it.chapterId == selectedChapterId }) selectedChapterId = null
        renderIouTabs()
        renderIouBody()
    }

    private fun renderIouTabs() {
        iouTabRow.removeAllViews()
        addIouTab("IOU", chapterId = null)
        latestChapters.forEach { addIouTab(it.name, it.chapterId) }
    }

    private fun addIouTab(label: String, chapterId: Long?) {
        val tab = TextView(this, null, 0, R.style.Widget_UPI_SectionTab).apply {
            text = label
            isSelected = chapterId == selectedChapterId
            setOnClickListener {
                if (selectedChapterId == chapterId) return@setOnClickListener
                selectedChapterId = chapterId
                renderIouTabs()
                renderIouBody()
            }
        }
        iouTabRow.addView(tab)
    }

    private fun renderIouBody() {
        val chapterId = selectedChapterId
        if (chapterId == null) {
            buildIouSection(latestIouSummaries)
            return
        }
        buildChapterPlanSection(latestChapters.first { it.chapterId == chapterId })
    }

    /**
     * A chapter's plan, drawn as the same cards a friend's IOU is.
     *
     * Deliberately the same shape: "Dan owes you 700" means the same thing whether it came from the
     * base ledger or from a chapter's simplification, and the only way to keep the two reading alike
     * is to draw them with one layout and one set of colour roles.
     */
    private fun buildChapterPlanSection(chapter: DashboardChapter) {
        iouContainer.removeAllViews()
        btnToggleInsignificantIou.visibility = View.GONE

        if (chapter.plan.isEmpty()) {
            iouContainer.addView(mutedNote("Nobody owes anybody."))
            return
        }

        chapter.plan.forEach { row ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_friend_iou, iouContainer, false)
            Avatars.bind(
                card.findViewById(R.id.tvFriendInitials), row.subjectName, ActorType.FRIEND, fallback = "F"
            )
            card.findViewById<TextView>(R.id.tvFriendName).text = row.subjectName
            card.findViewById<TextView>(R.id.tvIouLabel).text = row.label
            card.findViewById<TextView>(R.id.tvIouAmount).apply {
                text = AmountFormat.rupees(row.amountPaise)
                setTextColor(themeColor(row.direction.colorAttr()))
            }
            card.setOnClickListener {
                startActivity(
                    Intent(this, ChapterDetailActivity::class.java)
                        .putExtra(ChapterDetailActivity.EXTRA_CHAPTER_ID, chapter.chapterId)
                )
            }
            iouContainer.addView(card)
        }
    }

    /** The one empty-state shape these sections use. */
    private fun mutedNote(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 13f
        setTextColor(themeColor(ThemeAttr.textMuted))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun buildIouSection(summaries: List<FriendLedgerSummary>) {
        iouContainer.removeAllViews()
        if (summaries.isEmpty()) {
            iouContainer.addView(mutedNote("No IOU records yet"))
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
            iouContainer.addView(mutedNote("No significant IOUs"))
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
