package com.varun.upitracker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.themeColor
import java.text.SimpleDateFormat
import java.util.Date

/**
 * What a row shows that the entry itself does not say.
 *
 * Resolved by whoever loaded the list, off the main thread and in one pass: the name behind a friend
 * or merchant id costs a query, and the adapter is asked to bind the same row again on every filter
 * change, every selection tick and every recycle.
 */
data class TransactionRowInfo(
    /** The counterparty, or a transfer's type. */
    val title: String,
    /** Second line: the kind of transaction, a transfer's route, an IOU's state -- per screen. */
    val note: String = "",
    /**
     * What the second line says instead while a selection is running, if anything.
     *
     * Where a row cannot be shared, why not is more use than its IOU state to someone in the middle
     * of choosing what to send -- and less use than its IOU state the rest of the time.
     */
    val noteWhenSelecting: String? = null,
    /** Small figure under the amount: what this row did to a shared balance. */
    val trailing: String? = null,
    /** Theme attribute [trailing] is painted with. */
    val trailingAttr: Int = ThemeAttr.textMuted,
    /** Lower still: the running account balance, which only All Transactions fills in. */
    val balance: String? = null,
    val balanceAttr: Int = ThemeAttr.textMuted
)

/**
 * The one transaction list in the app.
 *
 * All Transactions, a friend's page and a chapter's transactions all draw the same card, all enter a
 * selection on long-press and all act on what is selected. They used to be two bespoke adapters and
 * a hand-built LinearLayout of label/amount lines, which is how the three drifted into looking like
 * three different lists of the same thing.
 *
 * Rows are keyed by [stableId] rather than by position or id: the list interleaves transactions,
 * whose ids are a Long, with account transfers, whose ids are a UUID string.
 */
class TransactionListAdapter(
    private val dateFmt: SimpleDateFormat,
    private val onTap: (LedgerEntry) -> Unit,
    private val onLongPress: (LedgerEntry) -> Unit
) : RecyclerView.Adapter<TransactionListAdapter.VH>() {

    private var entries: List<LedgerEntry> = emptyList()
    private var info: Map<String, TransactionRowInfo> = emptyMap()
    private var selectionMode = false
    private var selected: Set<String> = emptySet()

    /**
     * Rows that may be ticked. Empty means every row may be.
     *
     * A screen that can only act on some of what it shows -- sharing, where a row imported from a
     * friend cannot be passed on again -- greys the rest rather than hiding them, so the list does
     * not reshuffle the moment a selection starts.
     */
    private var selectable: Set<String> = emptySet()

    /**
     * Swaps the content, and redraws only if it actually changed.
     *
     * Every filter keystroke republishes the whole state, and a list rebuilt each time would jump
     * back to the top mid-tick. Compared by value, not by identity: the filtered list is a fresh
     * object on every emission, so an identity check would never hold.
     */
    fun submit(entries: List<LedgerEntry>, info: Map<String, TransactionRowInfo>) {
        if (this.entries == entries && this.info == info) return
        this.entries = entries
        this.info = info
        notifyDataSetChanged()
    }

    fun updateSelection(
        selectionMode: Boolean,
        selected: Set<String>,
        selectable: Set<String> = emptySet()
    ) {
        if (this.selectionMode == selectionMode &&
            this.selected == selected &&
            this.selectable == selectable
        ) {
            return
        }
        this.selectionMode = selectionMode
        this.selected = selected
        this.selectable = selectable
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val select: CheckBox = view.findViewById(R.id.cbFriendTxSelect)
        val title: TextView = view.findViewById(R.id.tvFriendTxPayee)
        val date: TextView = view.findViewById(R.id.tvFriendTxDate)
        val note: TextView = view.findViewById(R.id.tvFriendTxIouNote)
        val amount: TextView = view.findViewById(R.id.tvFriendTxAmount)
        val trailing: TextView = view.findViewById(R.id.tvFriendTxIouAmount)
        val balance: TextView = view.findViewById(R.id.tvFriendTxBalance)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_friend_transaction, parent, false)
    )

    override fun getItemCount() = entries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        val key = entry.stableId()
        val row = info[key]
        val pickable = selectable.isEmpty() || key in selectable

        // Every one of these is set on every bind, never inside a branch: a recycled holder would
        // otherwise keep the tick, the dimming or the trailing figure of whatever row it showed last.
        holder.select.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.select.isChecked = key in selected
        holder.select.isEnabled = pickable
        holder.itemView.alpha = if (selectionMode && !pickable) DIMMED_ALPHA else 1f

        holder.date.text = dateFmt.format(Date(entry.dateEpoch))
        holder.title.text = row?.title.orEmpty()
        holder.note.text = (if (selectionMode) row?.noteWhenSelecting ?: row?.note else row?.note).orEmpty()
        holder.note.setTextColor(holder.note.themeColor(ThemeAttr.secondary))

        holder.trailing.text = row?.trailing.orEmpty()
        row?.trailing?.let { holder.trailing.setTextColor(holder.trailing.themeColor(row.trailingAttr)) }

        val balance = row?.balance
        holder.balance.visibility = if (balance == null) View.GONE else View.VISIBLE
        holder.balance.text = balance.orEmpty()
        if (balance != null) {
            holder.balance.setTextColor(holder.balance.themeColor(row.balanceAttr))
        }

        when (entry) {
            is LedgerEntry.Tx -> {
                holder.amount.text = entry.transaction.formatPerspectiveAmount()
                holder.amount.setTextColor(entry.transaction.perspectiveColor(holder.amount.context))
            }
            is LedgerEntry.Transfer -> {
                holder.amount.text = entry.transfer.formatTransferAmount()
                holder.amount.setTextColor(AmountPerspective.NEUTRAL.color(holder.amount.context))
            }
        }

        holder.itemView.setOnClickListener { onTap(entry) }
        holder.itemView.setOnLongClickListener {
            onLongPress(entry)
            true
        }
    }

    private companion object {
        /** A row that cannot be acted on, while a selection is running. */
        const val DIMMED_ALPHA = 0.4f
    }
}
