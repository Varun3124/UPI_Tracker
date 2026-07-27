package com.varun.upitracker.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AllTransactionsActivity : AppCompatActivity() {

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_transactions)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<TextView>(R.id.btnBackAll).setOnClickListener { finish() }

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val startOfMonth = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.DAY_OF_MONTH, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            val txList = withContext(Dispatchers.IO) { db.transactionDao().getTransactionsSinceSync(startOfMonth) }
            findViewById<RecyclerView>(R.id.rvAllTransactions).apply {
                layoutManager = LinearLayoutManager(this@AllTransactionsActivity)
                adapter = AllTransactionsAdapter(txList, db, dateFmt) { txId ->
                    startActivity(Intent(this@AllTransactionsActivity, TransactionEntryActivity::class.java).apply {
                        putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, txId)
                    })
                }
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
        val tvPayee: TextView = view.findViewById(R.id.tvFriendTxPayee)
        val tvDate: TextView = view.findViewById(R.id.tvFriendTxDate)
        val tvNote: TextView = view.findViewById(R.id.tvFriendTxIouNote)
        val tvAmount: TextView = view.findViewById(R.id.tvFriendTxAmount)
        val tvIou: TextView = view.findViewById(R.id.tvFriendTxIouAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_friend_transaction, parent, false)
    )

    override fun getItemCount() = transactions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tx = transactions[position]
        CoroutineScope(Dispatchers.Main).launch {
            holder.tvPayee.text = withContext(Dispatchers.IO) { tx.resolvePrimaryDisplay(db) }
        }
        holder.tvDate.text = dateFmt.format(Date(tx.dateEpoch))
        holder.tvAmount.text = tx.formatPerspectiveAmount()
        holder.tvAmount.setTextColor(tx.perspectiveColor())
        holder.tvNote.text = tx.resolveTypeLabel()
        holder.tvIou.text = ""
        holder.itemView.setOnClickListener { onTap(tx.id) }
    }
}

private fun Transaction.perspectiveColor(): Int = when (amountPerspective()) {
    AmountPerspective.OUTGOING -> Color.parseColor("#C62828")
    AmountPerspective.INCOMING -> Color.parseColor("#2E7D32")
    AmountPerspective.NEUTRAL -> Color.parseColor("#AAAAAA")
}
