package com.varun.upitracker.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Back as the screen last looked, page by page: the weekly summary before the day tapped from it. */
class BackHistoryTest {

    private val statistics = Any()
    private val list = Any()

    @After
    fun forget() {
        BackHistory.release(statistics)
        BackHistory.release(list)
    }

    private fun <S> restore(snapshot: S) = BackStep.Restore(snapshot)

    @Test
    fun `back undoes changes newest first, then has nothing left`() {
        val history = PageHistory<String>(statistics)
        history.record("weekly", "daily, Tuesday")
        history.record("daily, Tuesday", "daily, Wednesday")
        assertEquals(restore("daily, Tuesday"), history.pop("daily, Wednesday"))
        assertEquals(restore("weekly"), history.pop("daily, Tuesday"))
        assertNull(history.pop("weekly"))
    }

    @Test
    fun `choosing what is already showing is no step, so back leaves the page`() {
        val history = PageHistory<String>(statistics)
        history.record("weekly", "weekly")
        assertNull(history.pop("weekly"))
    }

    @Test
    fun `a page that only looks the same is no step either`() {
        // Weekly picked again later the same week: the moment differs, the week shown does not.
        val history = PageHistory<Pair<String, Long>>(statistics) { a, b -> a.first == b.first && a.second / 7 == b.second / 7 }
        history.record("weekly" to 15L, "weekly" to 16L)
        assertNull(history.pop("weekly" to 16L))
    }

    @Test
    fun `the same look is never kept twice in a row`() {
        val history = PageHistory<String>(statistics)
        history.record("weekly", "daily")
        // Back to weekly by hand, then daily again: weekly is already the step on top.
        history.record("weekly", "daily")
        assertEquals(restore("weekly"), history.pop("daily"))
        assertNull(history.pop("weekly"))
    }

    @Test
    fun `however many filters go on, back from them is one step to none`() {
        val history = PageHistory<String>(list)
        history.filtersChanged(true)  // only pending
        history.filtersChanged(true)  // and shared
        history.filtersChanged(true)  // and a search
        assertEquals(BackStep.ClearFilters, history.pop("this month"))
        assertNull(history.pop("this month"))
    }

    @Test
    fun `filters taken off by hand cancel the step, so back leaves the page`() {
        val history = PageHistory<String>(list)
        history.filtersChanged(true)
        history.filtersChanged(false)
        assertNull(history.pop("this month"))
    }

    @Test
    fun `filters and months keep their own order`() {
        val history = PageHistory<String>(list)
        history.filtersChanged(true)
        history.record("this month", "last month")
        assertEquals(restore("this month"), history.pop("last month"))
        assertEquals(BackStep.ClearFilters, history.pop("this month"))
        assertNull(history.pop("this month"))
    }

    @Test
    fun `filters cleared by hand after a month change leave the month step`() {
        val history = PageHistory<String>(list)
        history.filtersChanged(true)
        history.record("this month", "last month")
        history.filtersChanged(false)
        assertEquals(restore("this month"), history.pop("last month"))
        assertNull(history.pop("this month"))
    }

    @Test
    fun `back passes over a step that looks just like the page now`() {
        val history = PageHistory<String>(list)
        history.record("this month", "last month")
        history.record("last month", "this month")
        // Back from this month: "last month" is the step on top, then "this month" -- already showing.
        assertEquals(restore("last month"), history.pop("this month"))
        assertNull(history.pop("this month"))
    }

    @Test
    fun `a page only ever takes back its own changes`() {
        val stats = PageHistory<String>(statistics)
        val transactions = PageHistory<String>(list)
        stats.record("weekly", "daily")
        transactions.filtersChanged(true)
        // Statistics is underneath: its entry is not on top, so back on it would wrongly reach past the list.
        assertNull(stats.pop("daily"))
        assertEquals(BackStep.ClearFilters, transactions.pop("this month"))
        assertEquals(restore("weekly"), stats.pop("daily"))
    }

    @Test
    fun `a page that goes takes its history with it, leaving the one beneath where it was`() {
        val stats = PageHistory<String>(statistics)
        val transactions = PageHistory<String>(list)
        stats.record("weekly", "daily")
        transactions.record("this month", "last month")
        transactions.filtersChanged(true)
        transactions.release()
        assertEquals(restore("weekly"), stats.pop("daily"))
    }
}
