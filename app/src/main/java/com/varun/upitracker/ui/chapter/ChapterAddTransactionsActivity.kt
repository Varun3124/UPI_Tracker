package com.varun.upitracker.ui.chapter

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.ui.formatRupees
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.padRootForSystemBars
import com.varun.upitracker.ui.theme.themeColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Picks untagged transactions to drop into a chapter in one go.
 *
 * Its own screen rather than a dialog: there are no fragments in this project, and a date range plus
 * a multi-select list is more than a dialog should carry.
 */
class ChapterAddTransactionsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAPTER_ID = "chapter_id"

        private val RANGES = listOf(
            "Last 30 days" to 30,
            "Last 90 days" to 90,
            "Last year" to 365,
            "Everything" to 0
        )
    }

    private lateinit var viewModel: ChapterAddViewModel
    private lateinit var adapter: CandidateAdapter
    private var chapterId: Long = -1L
    private var rangeIndex = 1
    private val selected = linkedSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapter_add_transactions)

        chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
        if (chapterId == -1L) {
            finish()
            return
        }

        viewModel = ViewModelProvider(
            this,
            ChapterViewModelFactory(applicationContext)
        )[ChapterAddViewModel::class.java]
        padRootForSystemBars(R.id.main)

        adapter = CandidateAdapter(selected, ::onRowTapped)

        findViewById<ImageButton>(R.id.btnBackChapterAdd).setOnClickListener { finish() }
        findViewById<View>(R.id.btnConfirmChapterAdd).setOnClickListener { confirm() }
        findViewById<View>(R.id.btnChapterAddRange).setOnClickListener { showRangeDialog() }
        findViewById<RecyclerView>(R.id.rvChapterAdd).apply {
            layoutManager = LinearLayoutManager(this@ChapterAddTransactionsActivity)
            adapter = this@ChapterAddTransactionsActivity.adapter
        }

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                state.chapter?.let {
                    findViewById<TextView>(R.id.tvChapterAddTitle).text = "Add to ${it.name}"
                }
                adapter.submit(state.rows)
                findViewById<TextView>(R.id.tvChapterAddEmpty).visibility =
                    if (!state.isLoading && state.rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        reload()
    }

    private fun reload() {
        val (label, days) = RANGES[rangeIndex]
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnChapterAddRange).text = label
        val to = System.currentTimeMillis() + 1
        val from = if (days == 0) 0L else Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -days)
        }.timeInMillis
        viewModel.load(chapterId, from, to)
    }

    private fun showRangeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Show transactions from")
            .setSingleChoiceItems(RANGES.map { it.first }.toTypedArray(), rangeIndex) { dialog, which ->
                rangeIndex = which
                // Selections are keyed by transaction id, so anything still in range survives.
                dialog.dismiss()
                reload()
            }
            .show()
    }

    private fun onRowTapped(row: TaggableRowUiState) {
        if (row.blockedReason != null) {
            Toast.makeText(this, row.blockedReason, Toast.LENGTH_SHORT).show()
            return
        }
        val id = row.transaction.id
        if (!selected.remove(id)) selected.add(id)
        adapter.notifyDataSetChanged()
    }

    private fun confirm() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Nothing picked", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.tagAll(
            chapterId = chapterId,
            transactionIds = selected.toSet(),
            onDone = { added ->
                // R5: tagging pulls everyone in a transaction into the chapter. Say so rather than
                // letting the membership quietly grow.
                if (added.isNotEmpty()) {
                    val names = added.joinToString(", ")
                    Toast.makeText(this, "Added $names to the chapter", Toast.LENGTH_LONG).show()
                }
                finish()
            },
            onError = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        )
    }

    private class CandidateAdapter(
        private val selected: Set<Long>,
        private val onTap: (TaggableRowUiState) -> Unit
    ) : RecyclerView.Adapter<CandidateAdapter.ViewHolder>() {

        private var items: List<TaggableRowUiState> = emptyList()
        private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

        fun submit(rows: List<TaggableRowUiState>) {
            items = rows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chapter_candidate, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], selected, dateFormat, onTap)
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val check: CheckBox = view.findViewById(R.id.cbChapterCandidate)
            private val title: TextView = view.findViewById(R.id.tvCandidateTitle)
            private val subtitle: TextView = view.findViewById(R.id.tvCandidateSubtitle)
            private val amount: TextView = view.findViewById(R.id.tvCandidateAmount)

            fun bind(
                row: TaggableRowUiState,
                selected: Set<Long>,
                dateFormat: SimpleDateFormat,
                onTap: (TaggableRowUiState) -> Unit
            ) {
                val blocked = row.blockedReason != null
                title.text = row.title
                amount.text = formatRupees(row.transaction.amountPaise)
                subtitle.text = row.blockedReason
                    ?: listOfNotNull(
                        dateFormat.format(Date(row.transaction.dateEpoch)),
                        row.transaction.reason?.takeIf { it.isNotBlank() },
                        "pending".takeIf { row.transaction.isPending }
                    ).joinToString(" · ")

                check.isEnabled = !blocked
                check.isChecked = !blocked && row.transaction.id in selected
                // Greyed rather than hidden: the reason a row cannot be taken is worth reading.
                val muted = itemView.themeColor(ThemeAttr.textMuted)
                title.setTextColor(if (blocked) muted else itemView.themeColor(ThemeAttr.onSurface))
                amount.setTextColor(if (blocked) muted else itemView.themeColor(ThemeAttr.onSurface))
                subtitle.setTextColor(if (blocked) itemView.themeColor(ThemeAttr.warning) else muted)

                itemView.setOnClickListener { onTap(row) }
            }
        }
    }
}
