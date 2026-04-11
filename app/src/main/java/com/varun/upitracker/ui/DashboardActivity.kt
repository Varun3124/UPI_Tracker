package com.varun.upitracker.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.varun.upitracker.R
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.ledger.LedgerManager
import com.varun.upitracker.overlay.OverlayService
import com.varun.upitracker.sms.SmsBacklogScanner
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class DashboardActivity : AppCompatActivity() {

    private lateinit var tvDailySpend:        TextView
    private lateinit var tvMonthlySpend:      TextView
    private lateinit var recentRow:           LinearLayout
    private lateinit var iouContainer:        LinearLayout
    private val dateFmt = SimpleDateFormat("dd MMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        tvDailySpend   = findViewById(R.id.tvDailySpend)
        tvMonthlySpend = findViewById(R.id.tvMonthlySpend)
        recentRow      = findViewById(R.id.recentTransactionsRow)
        iouContainer   = findViewById(R.id.iouContainer)

        findViewById<Button>(R.id.btnAddManual).setOnClickListener {
            launchManualEntry()
        }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()  // Refresh every time we come back to this screen

        // Re-scan on every launch to catch messages received since last open
        lifecycleScope.launch(Dispatchers.IO) {
            SmsBacklogScanner(applicationContext).scan()
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)

            // --- Spend totals ---
            val now          = System.currentTimeMillis()
            val startOfDay   = startOfDay(now)
            val startOfMonth = startOfMonth(now)

            val dailySpend   = withContext(Dispatchers.IO) {
                db.transactionDao().getTotalDebitSince(startOfDay) ?: 0L
            }
            val monthlySpend = withContext(Dispatchers.IO) {
                db.transactionDao().getTotalDebitSince(startOfMonth) ?: 0L
            }

            tvDailySpend.text   = "₹${"%.0f".format(dailySpend / 100.0)}"
            tvMonthlySpend.text = "₹${"%.0f".format(monthlySpend / 100.0)}"

            // --- Recent transactions (last 5) ---
            val recent = withContext(Dispatchers.IO) {
                db.transactionDao().getRecentTransactions(5)
            }
            buildRecentRow(recent, db)

            // --- IOU summaries ---
            val summaries = withContext(Dispatchers.IO) {
                LedgerManager(db).getAllSummaries()
            }
            buildIouSection(summaries)
        }
    }

    private suspend fun buildRecentRow(
        transactions: List<Transaction>,
        db: AppDatabase
    ) {
        recentRow.removeAllViews()

        transactions.forEach { tx ->
            val displayName = withContext(Dispatchers.IO) {
                when (tx.payeeType) {
                    "FRIEND"   -> tx.resolvedFriendId?.let { db.friendDao().getFriendById(it)?.name }
                    "MERCHANT" -> tx.resolvedMerchantId?.let { db.merchantDao().getMerchantById(it)?.name }
                    else       -> null
                } ?: tx.payeeRaw.ifEmpty { "Unknown" }
            }

            val cardView = LayoutInflater.from(this)
                .inflate(R.layout.item_transaction_card, recentRow, false)

            cardView.findViewById<TextView>(R.id.tvCardPayee).text  = displayName
            cardView.findViewById<TextView>(R.id.tvCardDate).text   = dateFmt.format(Date(tx.dateEpoch))

            val amountTv = cardView.findViewById<TextView>(R.id.tvCardAmount)
            val amount   = "₹${"%.0f".format(tx.amountPaise / 100.0)}"
            amountTv.text      = if (tx.direction == "DEBIT") "-$amount" else "+$amount"
            amountTv.setTextColor(if (tx.direction == "DEBIT")
                Color.parseColor("#C62828") else Color.parseColor("#2E7D32"))

            val pendingBadge = cardView.findViewById<TextView>(R.id.tvPendingBadge)
            pendingBadge.visibility = if (tx.isPending) View.VISIBLE else View.GONE

            cardView.setOnClickListener { openTransactionEntry(tx.id) }
            recentRow.addView(cardView)
        }

        // "View all" card at the end
        val viewAllCard = LayoutInflater.from(this)
            .inflate(R.layout.item_transaction_card, recentRow, false)
        viewAllCard.findViewById<TextView>(R.id.tvCardPayee).text  = "View All"
        viewAllCard.findViewById<TextView>(R.id.tvCardAmount).text = "→"
        viewAllCard.findViewById<TextView>(R.id.tvCardDate).text   = "This month"
        viewAllCard.setOnClickListener {
            startActivity(Intent(this, AllTransactionsActivity::class.java))
        }
        recentRow.addView(viewAllCard)
    }

    private fun buildIouSection(summaries: List<com.varun.upitracker.ledger.FriendLedgerSummary>) {
        iouContainer.removeAllViews()

        if (summaries.isEmpty()) {
            val empty = TextView(this).apply {
                text      = "No IOU records yet"
                textSize  = 13f
                setTextColor(Color.GRAY)
                setPadding(0, 8, 0, 8)
            }
            iouContainer.addView(empty)
            return
        }

        summaries.forEach { summary ->
            val card = LayoutInflater.from(this)
                .inflate(R.layout.item_friend_iou, iouContainer, false)

            val initials   = card.findViewById<TextView>(R.id.tvFriendInitials)
            val nameView   = card.findViewById<TextView>(R.id.tvFriendName)
            val labelView  = card.findViewById<TextView>(R.id.tvIouLabel)
            val amountView = card.findViewById<TextView>(R.id.tvIouAmount)

            nameView.text = summary.friendName

            // Avatar circle
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#5C6BC0"))
            }
            initials.background = circle
            initials.text = summary.friendName.split(" ")
                .filter { it.isNotEmpty() }.take(2)
                .joinToString("") { it.first().uppercaseChar().toString() }

            val net = summary.netBalancePaise
            when {
                net > 0 -> {
                    labelView.text  = "owes you"
                    amountView.text = "₹${"%.0f".format(net / 100.0)}"
                    amountView.setTextColor(Color.parseColor("#2E7D32"))
                }
                net < 0 -> {
                    labelView.text  = "you owe"
                    amountView.text = "₹${"%.0f".format(-net / 100.0)}"
                    amountView.setTextColor(Color.parseColor("#C62828"))
                }
                else -> {
                    labelView.text  = "settled"
                    amountView.text = "₹0"
                    amountView.setTextColor(Color.GRAY)
                }
            }

            card.setOnClickListener {
                val intent = Intent(this, FriendDetailActivity::class.java).apply {
                    putExtra(FriendDetailActivity.EXTRA_FRIEND_ID, summary.friendId)
                }
                startActivity(intent)
            }

            iouContainer.addView(card)
        }
    }

    private fun launchManualEntry() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val id = withContext(Dispatchers.IO) {
                db.transactionDao().insert(
                    com.varun.upitracker.database.entity.Transaction(
                        amountPaise = 0L,
                        direction   = "DEBIT",
                        payeeRaw    = "",
                        payeeType   = "UNKNOWN",
                        dateEpoch   = System.currentTimeMillis(),
                        source      = "MANUAL",
                        isPending   = true
                    )
                )
            }
            startForegroundService(
                Intent(applicationContext, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_TRANSACTION_ID, id)
                }
            )
        }
    }

    private fun openTransactionEntry(transactionId: Long) {
        startForegroundService(
            Intent(applicationContext, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_TRANSACTION_ID, transactionId)
            }
        )
    }

    // --- Date helpers ---

    private fun startOfDay(now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun startOfMonth(now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}