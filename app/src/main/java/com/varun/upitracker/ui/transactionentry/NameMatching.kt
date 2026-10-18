package com.varun.upitracker.ui.transactionentry

/**
 * Which suggestions an alias field offers for what has been typed.
 *
 * Everyone keeps the rule a plain `ArrayAdapter` applies -- the whole name, or any word of it,
 * starting with the text. The user's own entry is the exception: "Asha(Me)" is one word to that rule,
 * so "sha" or "me" never found it. It matches on any part of itself instead.
 */
object NameMatching {

    fun matches(item: String, query: CharSequence?, meLabel: String): Boolean {
        val typed = query?.toString()?.lowercase().orEmpty()
        if (typed.isEmpty()) return true
        if (item == meLabel) return item.lowercase().contains(typed)
        val name = item.lowercase()
        return name.startsWith(typed) || name.split(" ").any { it.startsWith(typed) }
    }
}
