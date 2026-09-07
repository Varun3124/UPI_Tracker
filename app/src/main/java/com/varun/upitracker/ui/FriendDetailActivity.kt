package com.varun.upitracker.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.database.AppDatabase
import com.varun.upitracker.database.entity.Transaction
import com.varun.upitracker.ui.transactionentry.TransactionEntryActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.varun.upitracker.util.AmountFormat
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.themeColor
import android.widget.ImageButton
import com.varun.upitracker.ui.theme.padRootForSystemBars

class FriendDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FRIEND_ID = "friend_id"
    }

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private lateinit var viewModel: FriendDetailViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_friend_detail)

        padRootForSystemBars(R.id.main)

        val friendId = intent.getLongExtra(EXTRA_FRIEND_ID, -1L)
        if (friendId == -1L) {
            finish()
            return
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        viewModel = ViewModelProvider(
            this,
            ScreenViewModelFactory(applicationContext)
        )[FriendDetailViewModel::class.java]
        viewModel.uiState.observe(this) { state ->
            if (state.isLoading) return@observe

            val db = AppDatabase.getInstance(applicationContext)
            val friend = state.friend ?: run {
                Toast.makeText(this, "Friend not found", Toast.LENGTH_SHORT).show()
                finish()
                return@observe
            }
            val summary = state.summary

            findViewById<TextView>(R.id.tvFriendDetailName).text = friend.name
            findViewById<TextView>(R.id.tvFriendDetailBalance).apply {
                val net = summary?.netBalancePaise ?: 0L
                text = when {
                    net > 0 -> "+" + AmountFormat.rupees(net)
                    net < 0 -> "-" + AmountFormat.rupees(-net)
                    else -> "Settled"
                }
                setTextColor(
                    themeColor(
                        when {
                            net > 0 -> ThemeAttr.positive
                            net < 0 -> ThemeAttr.negative
                            else -> ThemeAttr.amountNeutral
                        }
                    )
                )
            }

            findViewById<RecyclerView>(R.id.rvFriendTransactions).apply {
                layoutManager = LinearLayoutManager(this@FriendDetailActivity)
                adapter = FriendTransactionAdapter(
                    transactions = state.transactions,
                    friendId = friendId,
                    db = db,
                    dateFmt = dateFmt,
                    onTap = { txId ->
                        startActivity(Intent(this@FriendDetailActivity, TransactionEntryActivity::class.java).apply {
                            putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, txId)
                        })
                    },
                    onLongPress = { txId -> showDeleteDialog(friendId, txId) }
                )
            }
        }
        viewModel.load(friendId)
    }

    private fun showDeleteDialog(friendId: Long, transactionId: Long) {
        AlertDialog.Builder(this)
            .setTitle("Delete transaction?")
            .setMessage("This will delete the transaction and its shares.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteTransaction(friendId, transactionId) { error ->
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class FriendTransactionAdapter(
    private val transactions: List<Transaction>,
    private val friendId: Long,
    private val db: AppDatabase,
    private val dateFmt: SimpleDateFormat,
    private val onTap: (Long) -> Unit,
    private val onLongPress: (Long) -> Unit
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
        holder.tvAmount.text = tx.formatPerspectiveAmount()
        holder.tvAmount.setTextColor(tx.perspectiveColor(holder.tvAmount.context))

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
                        iouAmt > 0 -> "owes " + AmountFormat.rupees(iouAmt)
                        iouAmt < 0 -> "you owe " + AmountFormat.rupees(-iouAmt)
                        else -> ""
                    }
                    holder.tvIouAmount.setTextColor(
                        holder.tvIouAmount.themeColor(
                            if (iouAmt > 0) ThemeAttr.positive else ThemeAttr.negative
                        )
                    )
                }
                share != null -> {
                    holder.tvIouNote.text = "Friend share"
                    holder.tvIouAmount.text = AmountFormat.rupees(share.amountPaise)
                    holder.tvIouAmount.setTextColor(holder.tvIouAmount.themeColor(ThemeAttr.secondary))
                }
                else -> {
                    holder.tvIouNote.text = ""
                    holder.tvIouAmount.text = ""
                }
            }
        }

        holder.itemView.setOnClickListener { onTap(tx.id) }
        holder.itemView.setOnLongClickListener {
            onLongPress(tx.id)
            true
        }
    }
}
