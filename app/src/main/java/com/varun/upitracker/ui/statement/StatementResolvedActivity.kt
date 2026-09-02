package com.varun.upitracker.ui.statement

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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
import com.varun.upitracker.ui.formatPerspectiveAmount
import com.varun.upitracker.ui.perspectiveColor
import com.varun.upitracker.ui.resolvePrimaryDisplay
import com.varun.upitracker.ui.resolveTypeLabel
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only list of the transactions a statement import matched by UPI ref id. Reached from the
 * "N UPI entries resolved" row on [StatementImportActivity].
 */
class StatementResolvedActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TRANSACTION_IDS = "transaction_ids"

        fun createIntent(context: Context, transactionIds: LongArray): Intent =
            Intent(context, StatementResolvedActivity::class.java)
                .putExtra(EXTRA_TRANSACTION_IDS, transactionIds)
    }

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statement_resolved)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<TextView>(R.id.btnBackResolved).setOnClickListener { finish() }

        val ids = intent.getLongArrayExtra(EXTRA_TRANSACTION_IDS) ?: LongArray(0)
        val db = AppDatabase.getInstance(applicationContext)
        val recycler = findViewById<RecyclerView>(R.id.rvResolved)
        recycler.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            val transactions = withContext(Dispatchers.IO) {
                ids.toList().mapNotNull { db.transactionDao().getTransactionById(it) }
            }
            recycler.adapter = ResolvedAdapter(transactions, db, dateFmt) { id ->
                startActivity(
                    Intent(this@StatementResolvedActivity, TransactionEntryActivity::class.java)
                        .putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, id)
                )
            }
        }
    }
}

/** Mirrors AllTransactionsAdapter so a resolved entry looks the same here as it does there. */
private class ResolvedAdapter(
    private val transactions: List<Transaction>,
    private val db: AppDatabase,
    private val dateFmt: SimpleDateFormat,
    private val onTap: (Long) -> Unit
) : RecyclerView.Adapter<ResolvedAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val payee: TextView = view.findViewById(R.id.tvFriendTxPayee)
        val date: TextView = view.findViewById(R.id.tvFriendTxDate)
        val note: TextView = view.findViewById(R.id.tvFriendTxIouNote)
        val amount: TextView = view.findViewById(R.id.tvFriendTxAmount)
        val iou: TextView = view.findViewById(R.id.tvFriendTxIouAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_friend_transaction, parent, false)
    )

    override fun getItemCount() = transactions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val transaction = transactions[position]
        holder.date.text = dateFmt.format(Date(transaction.dateEpoch))
        holder.iou.text = ""
        holder.amount.text = transaction.formatPerspectiveAmount()
        holder.amount.setTextColor(transaction.perspectiveColor())
        holder.note.text = transaction.resolveTypeLabel()

        holder.payee.text = transaction.payeeRawLabel ?: transaction.payerRawLabel ?: ""
        CoroutineScope(Dispatchers.Main).launch {
            holder.payee.text = withContext(Dispatchers.IO) { transaction.resolvePrimaryDisplay(db) }
        }

        holder.itemView.setOnClickListener { onTap(transaction.id) }
    }
}
