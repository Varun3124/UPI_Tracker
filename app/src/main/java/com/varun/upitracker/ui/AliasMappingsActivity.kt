package com.varun.upitracker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.database.entity.FriendRawName
import com.varun.upitracker.database.entity.FriendUpiId
import com.varun.upitracker.database.entity.MerchantRawName
import com.varun.upitracker.database.entity.MerchantUpiId
import com.varun.upitracker.database.model.FriendAliasBundle
import com.varun.upitracker.database.model.MerchantAliasBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AliasMappingsActivity : AppCompatActivity() {

    private lateinit var repository: SettingsRepository
    private lateinit var mode: String
    private lateinit var adapter: AliasCardAdapter
    private lateinit var tvTitle: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alias_mappings)

        repository = SettingsRepository(applicationContext)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FRIEND

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        tvTitle = findViewById(R.id.tvAliasTitle)
        tvHint = findViewById(R.id.tvAliasHint)
        tvEmpty = findViewById(R.id.tvEmptyAliases)

        adapter = AliasCardAdapter(
            onRename = ::showRenameDialog,
            onDeleteAlias = ::confirmDeleteAlias,
            onMoveRaw = ::showMoveRawDialog,
            onMoveUpi = ::showMoveUpiDialog,
            onDeleteRaw = ::confirmDeleteRaw,
            onDeleteUpi = ::confirmDeleteUpi
        )

        bindHeader()

        findViewById<TextView>(R.id.btnBackAliases).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnAddAliasToolbar).setOnClickListener { showCreateDialog() }
        findViewById<RecyclerView>(R.id.rvAliasCards).apply {
            layoutManager = LinearLayoutManager(this@AliasMappingsActivity)
            adapter = this@AliasMappingsActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        loadAliases()
    }

    private fun bindHeader() {
        if (mode == MODE_MERCHANT) {
            tvTitle.text = "Merchant aliases"
            tvHint.text = "Each card is one merchant alias. Raw names and UPI IDs can be deleted or moved to another alias."
        } else {
            tvTitle.text = "Friend aliases"
            tvHint.text = "Each card is one friend alias. Raw names and UPI IDs can be deleted or moved to another alias."
        }
    }

    private fun loadAliases() {
        lifecycleScope.launch {
            val cards: List<AliasCardItem> = withContext(Dispatchers.IO) {
                if (mode == MODE_MERCHANT) {
                    repository.getMerchantAliasBundles().map { bundle: MerchantAliasBundle ->
                        AliasCardItem(
                            id = bundle.merchant.id,
                            title = bundle.merchant.name,
                            rawNames = bundle.rawNames
                                .sortedBy { it.rawName.lowercase() }
                                .map { AliasMappingItem(it.id, it.rawName) },
                            upiIds = bundle.upiIds
                                .sortedBy { it.upiId.lowercase() }
                                .map { AliasMappingItem(it.id, it.upiId) }
                        )
                    }
                } else {
                    repository.getFriendAliasBundles().map { bundle: FriendAliasBundle ->
                        AliasCardItem(
                            id = bundle.friend.id,
                            title = bundle.friend.name,
                            rawNames = bundle.rawNames
                                .sortedBy { it.rawName.lowercase() }
                                .map { AliasMappingItem(it.id, it.rawName) },
                            upiIds = bundle.upiIds
                                .sortedBy { it.upiId.lowercase() }
                                .map { AliasMappingItem(it.id, it.upiId) }
                        )
                    }
                }
            }
            adapter.submit(cards)
            tvEmpty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showCreateDialog() {
        showTextInputDialog("Create alias", "") { value ->
            lifecycleScope.launch {
                runMutation {
                    if (mode == MODE_MERCHANT) createMerchantAlias(value) else createFriendAlias(value)
                }
            }
        }
    }

    private fun showRenameDialog(item: AliasCardItem) {
        showTextInputDialog("Rename alias", item.title) { value ->
            lifecycleScope.launch {
                runMutation {
                    if (mode == MODE_MERCHANT) renameMerchantAlias(item.id, value) else renameFriendAlias(item.id, value)
                }
            }
        }
    }

    private fun confirmDeleteAlias(item: AliasCardItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete alias?")
            .setMessage("Delete '${item.title}'? History-linked aliases will be blocked.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    runMutation {
                        if (mode == MODE_MERCHANT) deleteMerchantAlias(item.id) else deleteFriendAlias(item.id)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoveRawDialog(card: AliasCardItem, mapping: AliasMappingItem) {
        showTextInputDialog("Move raw name", "") { destination ->
            lifecycleScope.launch {
                runMutation {
                    if (mode == MODE_MERCHANT) moveMerchantRawName(mapping.id, destination) else moveFriendRawName(mapping.id, destination)
                }
            }
        }
    }

    private fun showMoveUpiDialog(card: AliasCardItem, mapping: AliasMappingItem) {
        showTextInputDialog("Move UPI ID", "") { destination ->
            lifecycleScope.launch {
                runMutation {
                    if (mode == MODE_MERCHANT) moveMerchantUpiId(mapping.id, destination) else moveFriendUpiId(mapping.id, destination)
                }
            }
        }
    }

    private fun confirmDeleteRaw(card: AliasCardItem, mapping: AliasMappingItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete raw name?")
            .setMessage("Delete '${mapping.value}' from '${card.title}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    runMutation {
                        if (mode == MODE_MERCHANT) {
                            deleteMerchantRawName(MerchantRawName(id = mapping.id, merchantId = card.id, rawName = mapping.value))
                        } else {
                            deleteFriendRawName(FriendRawName(id = mapping.id, friendId = card.id, rawName = mapping.value))
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteUpi(card: AliasCardItem, mapping: AliasMappingItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete UPI ID?")
            .setMessage("Delete '${mapping.value}' from '${card.title}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    runMutation {
                        if (mode == MODE_MERCHANT) {
                            deleteMerchantUpiId(MerchantUpiId(id = mapping.id, merchantId = card.id, upiId = mapping.value))
                        } else {
                            deleteFriendUpiId(FriendUpiId(id = mapping.id, friendId = card.id, upiId = mapping.value))
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTextInputDialog(title: String, initialValue: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initialValue)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun runMutation(block: suspend SettingsRepository.() -> Unit) {
        try {
            withContext(Dispatchers.IO) { repository.block() }
            loadAliases()
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Could not update alias mappings.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val MODE_FRIEND = "FRIEND"
        const val MODE_MERCHANT = "MERCHANT"
        private const val EXTRA_MODE = "alias_mode"

        fun createIntent(context: Context, mode: String): Intent {
            return Intent(context, AliasMappingsActivity::class.java).putExtra(EXTRA_MODE, mode)
        }
    }
}

private data class AliasCardItem(
    val id: Long,
    val title: String,
    val rawNames: List<AliasMappingItem>,
    val upiIds: List<AliasMappingItem>
)

private data class AliasMappingItem(
    val id: Long,
    val value: String
)

private class AliasCardAdapter(
    private val onRename: (AliasCardItem) -> Unit,
    private val onDeleteAlias: (AliasCardItem) -> Unit,
    private val onMoveRaw: (AliasCardItem, AliasMappingItem) -> Unit,
    private val onMoveUpi: (AliasCardItem, AliasMappingItem) -> Unit,
    private val onDeleteRaw: (AliasCardItem, AliasMappingItem) -> Unit,
    private val onDeleteUpi: (AliasCardItem, AliasMappingItem) -> Unit
) : RecyclerView.Adapter<AliasCardAdapter.VH>() {

    private val items = mutableListOf<AliasCardItem>()

    fun submit(cards: List<AliasCardItem>) {
        items.clear()
        items.addAll(cards)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_alias_card, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.btnRename.setOnClickListener { onRename(item) }
        holder.btnDelete.setOnClickListener { onDeleteAlias(item) }
        bindSection(
            container = holder.rawContainer,
            values = item.rawNames,
            accent = "#424242",
            emptyLabel = "No raw names mapped yet.",
            onMove = { mapping -> onMoveRaw(item, mapping) },
            onDelete = { mapping -> onDeleteRaw(item, mapping) }
        )
        bindSection(
            container = holder.upiContainer,
            values = item.upiIds,
            accent = "#1565C0",
            emptyLabel = "No UPI IDs mapped yet.",
            onMove = { mapping -> onMoveUpi(item, mapping) },
            onDelete = { mapping -> onDeleteUpi(item, mapping) }
        )
    }

    private fun bindSection(
        container: LinearLayout,
        values: List<AliasMappingItem>,
        accent: String,
        emptyLabel: String,
        onMove: (AliasMappingItem) -> Unit,
        onDelete: (AliasMappingItem) -> Unit
    ) {
        container.removeAllViews()
        if (values.isEmpty()) {
            container.addView(TextView(container.context).apply {
                text = emptyLabel
                textSize = 12f
                setTextColor(Color.parseColor("#9E9E9E"))
            })
            return
        }
        values.forEach { mapping ->
            container.addView(
                LinearLayout(container.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 6, 0, 6)

                    addView(TextView(context).apply {
                        text = mapping.value
                        textSize = 13f
                        setTextColor(Color.parseColor(accent))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })

                    addView(makeActionText(context, "Move") { onMove(mapping) })
                    addView(makeActionText(context, "Delete", "#C62828") { onDelete(mapping) })
                }
            )
        }
    }

    private fun makeActionText(
        context: Context,
        label: String,
        color: String = "#2E7D32",
        onTap: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor(color))
            setPadding(16, 0, 0, 0)
            setOnClickListener { onTap() }
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvAliasName)
        val btnRename: TextView = view.findViewById(R.id.btnRenameAlias)
        val btnDelete: TextView = view.findViewById(R.id.btnDeleteAlias)
        val rawContainer: LinearLayout = view.findViewById(R.id.rawNamesContainer)
        val upiContainer: LinearLayout = view.findViewById(R.id.upiIdsContainer)
    }
}
