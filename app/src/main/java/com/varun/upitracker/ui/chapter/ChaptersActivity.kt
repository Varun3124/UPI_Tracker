package com.varun.upitracker.ui.chapter

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.varun.upitracker.database.entity.ChapterState
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.ui.formatRupees
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.padRootForSystemBars
import com.varun.upitracker.ui.theme.themeColor
import kotlinx.coroutines.launch

/** The list of chapters, and the way into each one. */
class ChaptersActivity : AppCompatActivity() {

    private lateinit var viewModel: ChaptersViewModel
    private lateinit var adapter: ChaptersAdapter
    private lateinit var tvEmpty: TextView
    private var friends: List<Friend> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapters)

        viewModel = ViewModelProvider(this, ChapterViewModelFactory(applicationContext))[ChaptersViewModel::class.java]
        padRootForSystemBars(R.id.main)

        tvEmpty = findViewById(R.id.tvEmptyChapters)
        adapter = ChaptersAdapter(onOpen = ::openChapter)

        findViewById<ImageButton>(R.id.btnBackChapters).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddChapter).setOnClickListener { showCreateDialog() }
        findViewById<RecyclerView>(R.id.rvChapters).apply {
            layoutManager = LinearLayoutManager(this@ChaptersActivity)
            adapter = this@ChaptersActivity.adapter
        }

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                friends = state.friends
                adapter.submit(state.chapters)
                tvEmpty.visibility =
                    if (!state.isLoading && state.chapters.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    private fun openChapter(chapterId: Long) {
        startActivity(
            Intent(this, ChapterDetailActivity::class.java)
                .putExtra(ChapterDetailActivity.EXTRA_CHAPTER_ID, chapterId)
        )
    }

    private fun showCreateDialog() {
        val nameInput = EditText(this).apply {
            setSingleLine()
        }
        val checked = BooleanArray(friends.size)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.space_m)
            setPadding(pad, pad, pad, 0)
            addView(nameInput)
        }

        AlertDialog.Builder(this)
            .setTitle("New chapter")
            .setView(container)
            .setPositiveButton("Next") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Give it a name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (friends.isEmpty()) {
                    viewModel.create(name, emptySet(), null, ::showError)
                } else {
                    showMemberDialog(name, checked)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMemberDialog(name: String, checked: BooleanArray) {
        AlertDialog.Builder(this)
            .setTitle("Who is in $name?")
            .setMultiChoiceItems(friends.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Create") { _, _ ->
                val memberIds = friends.filterIndexed { index, _ -> checked[index] }.map { it.id }.toSet()
                viewModel.create(name, memberIds, null, ::showError)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private class ChaptersAdapter(
        private val onOpen: (Long) -> Unit
    ) : RecyclerView.Adapter<ChaptersAdapter.ViewHolder>() {

        private var items: List<ChapterRowUiState> = emptyList()

        fun submit(rows: List<ChapterRowUiState>) {
            items = rows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position], onOpen)

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.tvChapterName)
            private val active: TextView = view.findViewById(R.id.tvChapterActive)
            private val subtitle: TextView = view.findViewById(R.id.tvChapterSubtitle)
            private val netLabel: TextView = view.findViewById(R.id.tvChapterNetLabel)
            private val net: TextView = view.findViewById(R.id.tvChapterNet)

            fun bind(row: ChapterRowUiState, onOpen: (Long) -> Unit) {
                name.text = row.chapter.name
                // "Active", "Shared", or whose it is when it is a friend's: "Alice's · live".
                active.visibility = if (row.badge != null) View.VISIBLE else View.GONE
                active.text = row.badge.orEmpty()

                val state = when {
                    row.chapter.state == ChapterState.CLOSED -> "Closed"
                    row.settled -> "Settled"
                    else -> "Open"
                }
                val members = if (row.memberCount == 1) "1 member" else "${row.memberCount} members"
                val pending = if (row.pendingCount > 0) " · ${row.pendingCount} pending" else ""
                subtitle.text = "$state · $members$pending"

                when {
                    row.myNetPaise > 0L -> {
                        netLabel.text = "owed to you"
                        net.text = formatRupees(row.myNetPaise)
                        net.setTextColor(itemView.themeColor(ThemeAttr.positive))
                    }
                    row.myNetPaise < 0L -> {
                        netLabel.text = "you owe"
                        net.text = formatRupees(-row.myNetPaise)
                        net.setTextColor(itemView.themeColor(ThemeAttr.negative))
                    }
                    else -> {
                        netLabel.text = ""
                        net.text = "Even"
                        net.setTextColor(itemView.themeColor(ThemeAttr.amountNeutral))
                    }
                }

                itemView.setOnClickListener { onOpen(row.chapter.id) }
            }
        }
    }
}
