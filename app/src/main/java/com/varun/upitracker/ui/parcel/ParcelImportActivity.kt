package com.varun.upitracker.ui.parcel

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.data.repository.ParcelEntry
import com.varun.upitracker.data.repository.ParcelImportPlan
import com.varun.upitracker.database.entity.Friend
import com.varun.upitracker.ui.settings.AppViewModelFactory
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.padRootForSystemBars
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.util.AmountFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Paste a friend's parcel, look through it, keep what is new.
 *
 * One Activity with two panels rather than two activities, matching
 * [com.varun.upitracker.ui.statement.StatementImportActivity] -- the plan is too big to pass
 * through an Intent, and nothing is written until Save.
 *
 * Note there is no `ACTION_SEND` intent-filter for this screen, deliberately. Accepting parcels
 * straight from a chat app would mean exporting an activity that writes to the ledger, and any
 * app on the device could then feed it. Pasting keeps the user in the loop.
 */
class ParcelImportActivity : AppCompatActivity() {

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private lateinit var viewModel: ParcelImportViewModel
    private var friends: List<Friend> = emptyList()
    private var adapter: ParcelRowAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parcel_import)
        padRootForSystemBars(R.id.main)

        viewModel = ViewModelProvider(this, AppViewModelFactory(this))[ParcelImportViewModel::class.java]

        findViewById<ImageButton>(R.id.btnBackParcel).setOnClickListener { onBack() }
        onBackPressedDispatcher.addCallback(this) { onBack() }

        findViewById<Spinner>(R.id.spinnerSender).onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    friends.getOrNull(position)?.let { viewModel.selectSender(it.id) }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        findViewById<EditText>(R.id.etParcel).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.setText(s?.toString().orEmpty())
            }
        })

        findViewById<View>(R.id.btnPasteParcel).setOnClickListener { pasteFromClipboard() }
        findViewById<View>(R.id.btnRead).setOnClickListener { viewModel.readParcel(::toast) }
        findViewById<View>(R.id.btnCommitParcel).setOnClickListener { commit() }

        viewModel.uiState.observe(this) { render(it) }
        viewModel.loadFriends()
    }

    private fun render(state: ParcelImportUiState) {
        val reviewing = state.plan != null
        findViewById<View>(R.id.panelSetup).visibility = if (reviewing) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnRead).visibility = if (reviewing) View.GONE else View.VISIBLE
        findViewById<View>(R.id.panelReview).visibility = if (reviewing) View.VISIBLE else View.GONE
        if (reviewing) renderReview(state) else renderSetup(state)
    }

    private fun renderSetup(state: ParcelImportUiState) {
        if (state.friends !== friends) {
            friends = state.friends
            findViewById<Spinner>(R.id.spinnerSender).adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                friends.map { it.name }
            )
        }
        findViewById<View>(R.id.tvNoFriends).visibility =
            if (friends.isEmpty()) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.btnRead).apply {
            isEnabled = state.canRead
            alpha = if (state.canRead) 1f else 0.5f
        }
    }

    private fun renderReview(state: ParcelImportUiState) {
        val plan = state.plan ?: return

        findViewById<TextView>(R.id.tvReviewSummary).text = buildString {
            append("From ${plan.senderName}. ")
            append(
                when (plan.entries.size) {
                    0 -> "Nothing new."
                    1 -> "1 transaction to look at."
                    else -> "${plan.entries.size} transactions to look at."
                }
            )
            if (plan.alreadyImported > 0) {
                append(
                    if (plan.alreadyImported == 1) " 1 was already imported."
                    else " ${plan.alreadyImported} were already imported."
                )
            }
        }

        // Naming a person only labels them; it never posts against them until the user says who
        // they are, which is what this line is asking.
        findViewById<TextView>(R.id.tvUnmappedPeople).apply {
            val names = plan.unmappedPeople.filterNot { it in state.personMappings }
            visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
            text = "Also in these splits: ${names.joinToString(", ")}. " +
                "They stay as names only — tap a transaction to link them to someone."
        }

        findViewById<View>(R.id.tvReviewEmpty).visibility =
            if (plan.entries.isEmpty()) View.VISIBLE else View.GONE

        renderRows(state, plan)

        findViewById<TextView>(R.id.btnCommitParcel).apply {
            val count = state.willCreate
            text = when {
                state.busy -> "Saving…"
                count == 0 -> "Nothing to save"
                count == 1 -> "Save 1 pending transaction"
                else -> "Save $count pending transactions"
            }
            isEnabled = count > 0 && !state.busy
            alpha = if (isEnabled) 1f else 0.5f
        }
    }

    private fun renderRows(state: ParcelImportUiState, plan: ParcelImportPlan) {
        val recycler = findViewById<RecyclerView>(R.id.rvParcelRows)
        val existing = adapter
        if (existing == null || existing.entries !== plan.entries) {
            adapter = ParcelRowAdapter(
                entries = plan.entries,
                senderName = plan.senderName,
                dateFmt = dateFmt,
                onToggleSkip = { index -> viewModel.toggleSkipped(index) },
                onMapPeople = { entry -> showPersonMapping(entry) }
            ).also {
                recycler.layoutManager = LinearLayoutManager(this)
                recycler.adapter = it
            }
        }
        adapter?.updateState(state.skipped, state.personMappings)
    }

    /**
     * Linking a name to a friend is what turns a label into something the ledger will post
     * against, so it is always an explicit choice, and always reversible here.
     */
    private fun showPersonMapping(entry: ParcelEntry) {
        val names = entry.preview.unmappedPeople
        if (names.isEmpty()) return
        val state = viewModel.uiState.value ?: return
        val plan = state.plan ?: return
        val candidates = friends.filter { it.id != plan.senderFriendId }

        fun chooseFor(name: String) {
            val options = (listOf("Leave as a name only") + candidates.map { it.name }).toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Who is \"$name\"?")
                .setItems(options) { _, index ->
                    viewModel.mapPerson(name, candidates.getOrNull(index - 1)?.id)
                }
                .show()
        }

        if (names.size == 1) {
            chooseFor(names.first())
        } else {
            AlertDialog.Builder(this)
                .setTitle("Who was in this split?")
                .setItems(names.toTypedArray()) { _, index -> chooseFor(names[index]) }
                .show()
        }
    }

    private fun pasteFromClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast("There is nothing on your clipboard.")
            return
        }
        findViewById<EditText>(R.id.etParcel).setText(text)
    }

    private fun commit() {
        viewModel.commit(
            onDone = { result ->
                toast(
                    when (result.created) {
                        0 -> "Nothing was saved."
                        1 -> "1 pending transaction saved. Confirm it to move your balance."
                        else -> "${result.created} pending transactions saved. Confirm them to move your balance."
                    }
                )
                finish()
            },
            onError = ::toast
        )
    }

    private fun onBack() {
        if (viewModel.uiState.value?.plan != null) viewModel.backToSetup() else finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

private class ParcelRowAdapter(
    val entries: List<ParcelEntry>,
    private val senderName: String,
    private val dateFmt: SimpleDateFormat,
    private val onToggleSkip: (Int) -> Unit,
    private val onMapPeople: (ParcelEntry) -> Unit
) : RecyclerView.Adapter<ParcelRowAdapter.VH>() {

    private var skipped: Set<Int> = emptySet()
    private var personMappings: Map<String, Long> = emptyMap()

    fun updateState(skipped: Set<Int>, personMappings: Map<String, Long>) {
        if (this.skipped == skipped && this.personMappings == personMappings) return
        this.skipped = skipped
        this.personMappings = personMappings
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val amount: TextView = view.findViewById(R.id.tvParcelAmount)
        val date: TextView = view.findViewById(R.id.tvParcelDate)
        val parties: TextView = view.findViewById(R.id.tvParcelParties)
        val reason: TextView = view.findViewById(R.id.tvParcelReason)
        val effect: TextView = view.findViewById(R.id.tvParcelEffect)
        val status: TextView = view.findViewById(R.id.tvParcelStatus)
        val candidates: ViewGroup = view.findViewById(R.id.containerParcelCandidates)
        val skip: TextView = view.findViewById(R.id.btnParcelSkip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_parcel_row, parent, false)
    )

    override fun getItemCount() = entries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        val preview = entry.preview
        val context = holder.itemView.context
        val isDuplicate = entry.certainDuplicateId != null
        val isSkipped = position in skipped || isDuplicate

        holder.amount.text = AmountFormat.rupees(preview.amountPaise)
        holder.date.text = dateFmt.format(Date(preview.dateEpoch))
        holder.parties.text = "${preview.payerLabel} → ${preview.payeeLabel}"

        holder.reason.visibility = if (preview.reason.isNullOrBlank()) View.GONE else View.VISIBLE
        holder.reason.text = preview.reason

        val delta = preview.myBalanceDeltaPaise
        holder.effect.text = when {
            isSkipped -> "Will not be saved"
            delta > 0 -> "$senderName will owe you ${AmountFormat.rupees(delta)}"
            delta < 0 -> "You will owe $senderName ${AmountFormat.rupees(-delta)}"
            else -> "Changes no balance"
        }
        holder.effect.setTextColor(
            context.themeColor(
                when {
                    isSkipped -> ThemeAttr.textMuted
                    delta > 0 -> ThemeAttr.positive
                    delta < 0 -> ThemeAttr.negative
                    else -> ThemeAttr.amountNeutral
                }
            )
        )
        holder.itemView.alpha = if (isSkipped) 0.5f else 1f

        val notes = buildList {
            if (isDuplicate) add("You already have this one — it will be left alone.")
            if (preview.needsReview) add("You will need to open this one to confirm it.")
            val unmapped = preview.unmappedPeople.filterNot { it in personMappings }
            if (unmapped.isNotEmpty()) add("Also in the split: ${unmapped.joinToString(", ")}")
        }
        holder.status.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        holder.status.text = notes.joinToString("\n")

        bindCandidates(holder, entry, position)

        holder.skip.visibility = if (isDuplicate) View.GONE else View.VISIBLE
        holder.skip.text = if (position in skipped) "Save this after all" else "I already have this"
        holder.skip.setOnClickListener { onToggleSkip(position) }

        holder.itemView.setOnClickListener {
            if (preview.unmappedPeople.isNotEmpty()) onMapPeople(entry)
        }
    }

    /**
     * Rebuilt rather than recycled: a row carries none most of the time and a handful at most,
     * which is the same trade the statement importer makes for the same reason.
     */
    private fun bindCandidates(holder: VH, entry: ParcelEntry, position: Int) {
        holder.candidates.removeAllViews()
        if (entry.certainDuplicateId != null || entry.candidates.isEmpty()) {
            holder.candidates.visibility = View.GONE
            return
        }
        holder.candidates.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(holder.itemView.context)
        entry.candidates.take(3).forEach { candidate ->
            val view = inflater.inflate(R.layout.item_parcel_candidate, holder.candidates, false)
            view.findViewById<TextView>(R.id.tvParcelCandidateName).text =
                candidate.transaction.reason ?: "Your own record"
            view.findViewById<TextView>(R.id.tvParcelCandidateAmount).text =
                AmountFormat.rupees(candidate.transaction.amountPaise)
            view.findViewById<TextView>(R.id.tvParcelCandidateDate).text =
                dateFmt.format(Date(candidate.transaction.dateEpoch))
            view.findViewById<TextView>(R.id.tvParcelCandidateHint).visibility =
                if (candidate.counterpartyMatch) View.VISIBLE else View.GONE
            view.findViewById<ImageView>(R.id.tvParcelCandidateCheck).visibility =
                if (position in skipped) View.VISIBLE else View.GONE
            view.setOnClickListener { onToggleSkip(position) }
            holder.candidates.addView(view)
        }
    }
}
