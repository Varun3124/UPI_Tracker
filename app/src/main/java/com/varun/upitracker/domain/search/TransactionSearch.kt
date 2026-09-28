package com.varun.upitracker.domain.search

/**
 * Matching a typed query against the text a transaction row carries.
 *
 * Pure and Android-free so the matching rules can be asserted without a device -- and kept out of
 * the screen because the fields it is given are assembled once per load, not per keystroke: the
 * name behind a friend or merchant id costs a database read, and resolving it inside the filter
 * would do that for every row on every letter typed.
 */
object TransactionSearch {

    /**
     * Whether [fields] satisfies [query].
     *
     * Every space-separated term has to appear in *some* field, so `"dan coffee"` finds Dan's coffee
     * note without the two words having to sit in the same field. Case-insensitive, nulls and blanks
     * ignored, and a blank query matches everything -- an empty search box is not a filter.
     *
     * Split on the space alone rather than on a whitespace class: the query comes from a
     * single-line field, which cannot hold a tab or a newline, and `isNotBlank` already drops the
     * empty strings a run of spaces produces.
     */
    fun matches(fields: List<String?>, query: String): Boolean {
        val terms = query.lowercase().split(" ").filter { it.isNotBlank() }
        if (terms.isEmpty()) return true
        val haystack = fields.mapNotNull { field ->
            field?.lowercase()?.takeIf { it.isNotBlank() }
        }
        if (haystack.isEmpty()) return false
        return terms.all { term -> haystack.any { it.contains(term) } }
    }
}
