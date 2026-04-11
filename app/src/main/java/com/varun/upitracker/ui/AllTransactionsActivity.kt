package com.varun.upitracker.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.overlay.OverlayService
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class AllTransactionsActivity : AppCompatActivity() {

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_transactions)

        findViewById<TextView>(R.id.btnBackAll).setOnClickListener { finish() }

        lifecycleScope.launch {
            val db  = AppDatabase.getInstance(applicationContext)
            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfMonth = cal.timeInMillis

            val txList = withContext(Dispatchers.IO) {
                db.transactionDao().getTransactionsSinceSync(startOfMonth)
            }

            val rv = findViewById<RecyclerView>(R.id.rvAllTransactions)
            rv.layoutManager = LinearLayoutManager(this@AllTransactionsActivity)
            rv.adapter = AllTransactionsAdapter(txList, db, dateFmt) { txId ->
                startForegroundService(
                    Intent(applicationContext, OverlayService::class.java).apply {
                        putExtra(OverlayService.EXTRA_TRANSACTION_ID, txId)
                    }
                )
            }
        }
    }
}

class AllTransactionsAdapter(
    private val transactions: List<Transaction>,
    private val db: AppDatabase,
    private val dateFmt: SimpleDateFormat,
    private val onTap: (Long) -> Unit
) : RecyclerView.Adapter<AllTransactionsAdapter.VH>() {

    inner class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val tvPayee:  TextView = view.findViewById(R.id.tvFriendTxPayee)
        val tvDate:   TextView = view.findViewById(R.id.tvFriendTxDate)
        val tvNote:   TextView = view.findViewById(R.id.tvFriendTxIouNote)
        val tvAmount: TextView = view.findViewById(R.id.tvFriendTxAmount)
        val tvIou:    TextView = view.findViewById(R.id.tvFriendTxIouAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_friend_transaction, parent, false)
    )

    override fun getItemCount() = transactions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tx = transactions[position]

        CoroutineScope(Dispatchers.Main).launch {
            val displayName = withContext(Dispatchers.IO) {
                when (tx.payeeType) {
                    "FRIEND"   -> tx.resolvedFriendId?.let { db.friendDao().getFriendById(it)?.name }
                    "MERCHANT" -> tx.resolvedMerchantId?.let { db.merchantDao().getMerchantById(it)?.name }
                    else       -> null
                } ?: tx.payeeRaw.ifEmpty { "Unknown" }
            }
            holder.tvPayee.text = displayName
        }

        holder.tvDate.text = dateFmt.format(Date(tx.dateEpoch))

        val amount = "₹${"%.0f".format(tx.amountPaise / 100.0)}"
        holder.tvAmount.text = if (tx.direction == "DEBIT") "-$amount" else "+$amount"
        holder.tvAmount.setTextColor(if (tx.direction == "DEBIT")
            Color.parseColor("#C62828") else Color.parseColor("#2E7D32"))

        holder.tvNote.text = when {
            tx.isPending         -> "⚠ Pending"
            tx.payeeType == "UNKNOWN" -> "Uncategorised"
            else                 -> tx.payeeType.lowercase().replaceFirstChar { it.uppercase() }
        }

        holder.tvIou.text = ""
        holder.itemView.setOnClickListener { onTap(tx.id) }
    }
}