package com.varun.upitracker.ui.transactionentry

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

/** An alias field's dropdown, filtered by [NameMatching] rather than `ArrayAdapter`'s own rule. */
class NameSuggestionAdapter(
    context: Context,
    private val names: List<String>,
    private val meLabel: String
) : ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, names.toMutableList()) {

    private val filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val matched = names.filter { NameMatching.matches(it, constraint, meLabel) }
            return FilterResults().apply {
                values = matched
                count = matched.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            setNotifyOnChange(false)
            clear()
            addAll((results?.values as? List<String>).orEmpty())
            if (count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
        }
    }

    override fun getFilter(): Filter = filter
}
