package com.varun.upitracker.ui

/**
 * One stack, for the whole app, of how each open page looked before each change made on it.
 *
 * Back steps through it before it leaves a page: from the daily summary a bar was tapped into, back
 * is the weekly summary it was tapped from, with the same week and section showing. Only when the
 * page on top has nothing left here does back leave it, and the page beneath picks up its own
 * history where it was left. So back is always the screen as it last looked, page after page, down
 * to the dashboard.
 *
 * Filters are one step, not one per filter. Narrowing a list from how it opened leaves a single
 * "filters applied" entry, however many filters follow; back from filtered results is the results
 * as the page opened, not the filter before last. Taking every filter off by hand removes that
 * entry again -- the two cancel out -- so back from there leaves the page. See
 * [PageHistory.filtersChanged].
 *
 * Only real changes are steps. Choosing what is already showing -- Weekly while on this week's
 * summary -- keeps nothing, and the same look is never kept twice in a row, so every press of back
 * visibly changes the screen or leaves it. See [PageHistory.record].
 *
 * Each entry belongs to an owner -- a page's ViewModel, which outlives a rotation but not the page
 * -- and only that owner can take it back off. The page on top is always the one changing, so its
 * entries are always the top ones; and since a page that goes takes its entries with it
 * ([release]), the top entry always belongs to the page on screen. In memory only: if Android
 * reclaims the app, pages reopen as they were saved and simply have no history to step back through.
 */
object BackHistory {

    private class Entry(val owner: Any, val snapshot: Any, val key: String?)

    private val entries = ArrayList<Entry>()

    /**
     * Remembers [snapshot], how [owner]'s page looked just before a change.
     *
     * A run of changes sharing a [key] is one step: while the top entry is [owner]'s with the same
     * key, nothing more is kept; any other change in between starts a new step.
     */
    @Synchronized
    fun push(owner: Any, snapshot: Any, key: String? = null) {
        val top = entries.lastOrNull()
        if (key != null && top != null && top.owner === owner && top.key == key) return
        entries += Entry(owner, snapshot, key)
    }

    /** [owner]'s top snapshot, left where it is; null when it has none on top. */
    @Synchronized
    fun peek(owner: Any): Any? = entries.lastOrNull()?.takeIf { it.owner === owner }?.snapshot

    /** Whether [owner] has an entry under [key] anywhere in the stack. */
    @Synchronized
    fun has(owner: Any, key: String): Boolean = entries.any { it.owner === owner && it.key == key }

    /** Takes [owner]'s last snapshot back off, or null when it has none on top. */
    @Synchronized
    fun pop(owner: Any): Any? {
        val top = entries.lastOrNull()?.takeIf { it.owner === owner } ?: return null
        entries.removeAt(entries.lastIndex)
        return top.snapshot
    }

    /** Forgets [owner]'s entries under [key], wherever they sit. */
    @Synchronized
    fun remove(owner: Any, key: String) {
        entries.removeAll { it.owner === owner && it.key == key }
    }

    /** Forgets everything [owner] kept: its page has gone. */
    @Synchronized
    fun release(owner: Any) {
        entries.removeAll { it.owner === owner }
    }
}

/** What back does next on a page. */
sealed interface BackStep<out S> {

    /** Put the page back to how it looked before its last change. */
    data class Restore<S>(val snapshot: S) : BackStep<S>

    /** Take the filters back to how the page opened. */
    data object ClearFilters : BackStep<Nothing>
}

/**
 * A page's view of [BackHistory], typed to whatever that page snapshots of the things that are not
 * filters -- which month, which section. Filters are tracked apart, as a single step.
 *
 * [sameAs] says when two snapshots would look the same on screen. Plain equality by default; a page
 * whose snapshot holds something finer than what it shows -- the exact moment a week was opened,
 * where only the week is drawn -- says so here, so that is not mistaken for a change.
 */
class PageHistory<S : Any>(
    private val owner: Any,
    private val sameAs: (S, S) -> Boolean = { a, b -> a == b }
) {

    private object FiltersApplied

    /**
     * Keeps [before] as a step back from [after], how the page looks now that the change is made --
     * unless the change made no difference, or the page already has that same look on top.
     */
    @Suppress("UNCHECKED_CAST")
    fun record(before: S, after: S, key: String? = null) {
        if (sameAs(before, after)) return
        val top = BackHistory.peek(owner)
        if (top != null && top !== FiltersApplied && sameAs(top as S, before)) return
        BackHistory.push(owner, before, key)
    }

    /**
     * Called after any filter changes, with whether the page is now filtered away from how it
     * opened. The first filter leaves one "filters applied" step; more filters add nothing; and
     * coming back to how it opened takes the step away again.
     */
    fun filtersChanged(filtered: Boolean) {
        if (!filtered) return BackHistory.remove(owner, FILTERS)
        if (!BackHistory.has(owner, FILTERS)) BackHistory.push(owner, FiltersApplied, FILTERS)
    }

    /**
     * What back does now, or null when it should leave the page. A look identical to [current] is
     * passed over, so back never seems to do nothing.
     */
    @Suppress("UNCHECKED_CAST")
    fun pop(current: S): BackStep<S>? {
        while (true) {
            val previous = BackHistory.pop(owner) ?: return null
            if (previous === FiltersApplied) return BackStep.ClearFilters
            if (!sameAs(previous as S, current)) return BackStep.Restore(previous)
        }
    }

    /** Called from the owning ViewModel's `onCleared`. */
    fun release() = BackHistory.release(owner)

    private companion object {
        const val FILTERS = "filters"
    }
}
