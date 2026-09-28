package com.varun.upitracker.domain.statistics

/**
 * A period figure spread over the days it covers.
 *
 * `Rs14,200 this month` reads very differently on the 3rd than on the 28th, so every period total
 * the app shows carries a rate beside it. Pure and Android-free, like the rest of this package, so
 * the boundary rules can actually be tested.
 */
object PerDayRate {

    /**
     * Whole days of `[fromInclusive, toInclusive]` that have happened, counting both the first day
     * and today.
     *
     * Bounded at [nowEpoch], which is what makes the rate mean "so far" for a period in progress
     * and "over its whole length" for one already past. Zero when the period has not started.
     *
     * Takes bare epochs rather than a [DateRange] on purpose: `ALL_TIME`'s lower bound is
     * [Long.MIN_VALUE], and turning that into an inclusive start would overflow. Resolving what
     * "all time" begins at is the caller's business.
     */
    fun daysElapsed(fromInclusive: Long, toInclusive: Long, nowEpoch: Long): Int {
        val end = minOf(toInclusive, nowEpoch)
        if (end < fromInclusive) return 0
        val firstDay = StatisticsPeriods.startOfDay(fromInclusive)
        val lastDay = StatisticsPeriods.startOfDay(end)
        if (lastDay < firstDay) return 0

        // Estimated by division and then corrected by field arithmetic. A DST day is 23 or 25 hours
        // long, so the quotient can be one out in either direction -- which is exactly the error
        // StatisticsPeriods.addDays exists to avoid, and why the answer is not simply the quotient.
        var days = ((lastDay - firstDay) / StatisticsPeriods.DAY_MILLIS).toInt()
        while (days > 0 && StatisticsPeriods.addDays(firstDay, days) > lastDay) days--
        while (StatisticsPeriods.addDays(firstDay, days + 1) <= lastDay) days++
        return days + 1
    }

    /**
     * [paise] per day over [days], or null when there is no rate worth showing.
     *
     * A single day's rate *is* the figure itself, so it is left out rather than printed twice --
     * the same reason the dashboard shows no rate beside Today.
     */
    fun perDayPaise(paise: Long, days: Int): Long? = if (days <= 1) null else paise / days
}
