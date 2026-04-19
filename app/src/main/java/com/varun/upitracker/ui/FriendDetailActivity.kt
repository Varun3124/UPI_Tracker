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
import com.varun.upitracker.ledger.LedgerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FriendDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FRIEND_ID = "friend_id"
    }

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_friend_detail)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val friendId = intent.getLongExtra(EXTRA_FRIEND_ID, -1L)
        if (friendId == -1L) {
            finish()
            return
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val friend = withContext(Dispatchers.IO) { db.friendDao().getFriendById(friendId) } ?: run {
                finish()
                return@launch
            }
            val summary = withContext(Dispatchers.IO) { LedgerManager(db).getSummaryForFriend(friendId) }
            val txList = withContext(Dispatchers.IO) { db.transactionDao().getTransactionsForFriendSync(friendId) }

            findViewById<TextView>(R.id.tvFriendDetailName).text = friend.name
            findViewById<TextView>(R.id.tvFriendDetailBalance).apply {
                val net = summary?.netBalancePaise ?: 0L
                text = when {
                    net > 0 -> "+Rs${"%.0f".format(net / 100.0)}"
                    net < 0 -> "-Rs${"%.0f".format(-net / 100.0)}"
                    else -> "Settled"
                }
                setTextColor(
                    when {
                        net > 0 -> Color.parseColor("#2E7D32")
                        net < 0 -> Color.parseColor("#C62828")
                        else -> Color.GRAY
                    }
                )
            }

            findViewById<RecyclerView>(R.id.rvFriendTransactions).apply {
                layoutManager = LinearLayoutManager(this@FriendDetailActivity)
                adapter = FriendTransactionAdapter(txList, friendId, db, dateFmt) { txId ->
                    startActivity(Intent(this@FriendDetailActivity, TransactionEntryActivity::class.java).apply {
                        putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, txId)
                    })
                }
            }
        }
    }
}

class FriendTransactionAdapter(
    private val transactions: List<Transaction>,
    private val friendId: Long,
    private val db: AppDatabase,
    private val dateFmt: SimpleDateFormat,
    private val onTap: (Long) -> Unit
) : RecyclerView.Adapter<FriendTransactionAdapter.VH>() {

    inner class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val tvPayee: TextView = view.findViewById(R.id.tvFriendTxPayee)
        val tvDate: TextView = view.findViewById(R.id.tvFriendTxDate)
        val tvIouNote: TextView = view.findViewById(R.id.tvFriendTxIouNote)
        val tvAmount: TextView = view.findViewById(R.id.tvFriendTxAmount)
        val tvIouAmount: TextView = view.findViewById(R.id.tvFriendTxIouAmount)
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
        val amount = "Rs${"%.0f".format(tx.amountPaise / 100.0)}"
        holder.tvAmount.text = if (tx.direction == "DEBIT") "-$amount" else "+$amount"
        holder.tvAmount.setTextColor(if (tx.direction == "DEBIT") Color.parseColor("#C62828") else Color.parseColor("#2E7D32"))

        CoroutineScope(Dispatchers.Main).launch {
            val entries = withContext(Dispatchers.IO) { db.iouDao().getEntriesForTransaction(tx.id).filter { it.friendId == friendId } }
            val share = withContext(Dispatchers.IO) {
                db.transactionShareDao().getSharesForTransaction(tx.id)
                    .firstOrNull { it.participantType == ActorType.FRIEND && it.friendId == friendId }
            }
            when {
                entries.isNotEmpty() -> {
                    val iouAmt = entries.sumOf { it.amountPaise }
                    val settled = entries.all { it.isSettled }
                    holder.tvIouNote.text = if (settled) "IOU settled" else "IOU pending"
                    holder.tvIouAmount.text = when {
                        iouAmt > 0 -> "owes Rs${"%.0f".format(iouAmt / 100.0)}"
                        iouAmt < 0 -> "you owe Rs${"%.0f".format(-iouAmt / 100.0)}"
                        else -> ""
                    }
                    holder.tvIouAmount.setTextColor(if (iouAmt > 0) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
                }
                share != null -> {
                    holder.tvIouNote.text = if (share.shareSide == ShareSide.MEANT_TO_PAY) "Friend meant to pay" else "Friend meant to receive"
                    holder.tvIouAmount.text = "Rs${"%.0f".format(share.amountPaise / 100.0)}"
                    holder.tvIouAmount.setTextColor(Color.parseColor("#5C6BC0"))
                }
                else -> {
                    holder.tvIouNote.text = ""
                    holder.tvIouAmount.text = ""
                }
            }
        }

        holder.itemView.setOnClickListener { onTap(tx.id) }
    }
}
