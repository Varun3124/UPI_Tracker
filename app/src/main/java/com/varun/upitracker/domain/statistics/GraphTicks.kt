package com.varun.upitracker.domain.statistics

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A labelled mark on the time axis. */
data class TimeTick(val epoch: Long, val label: String)

/** The gridlines for one axis: labelled majors, and the fainter minors between them. */
data class AxisTicks<T>(val major: List<T>, val minor: List<T>)

/**
 * Where the balance graph's gridlines fall, Desmos style: majors far enough apart to label, minors
 * between them, both on round values -- 1, 2 and 5 times a power of ten up the balance axis, and
 * calendar boundaries across time -- so the grid slides with the graph rather than being redrawn
 * in new places as it moves.
 */
object GraphTicks {

    /**
     * Balance gridlines across `[min, max]` paise, majors at least [minSpacingPx] apart on a plot
     * [heightPx] tall. Steps never go below a rupee: nobody reads a balance to the paisa off a graph.
     */
    fun valueTicks(min: Double, max: Double, heightPx: Float, minSpacingPx: Float): AxisTicks<Double> {
        if (max <= min || heightPx <= 0f) return AxisTicks(emptyList(), emptyList())
        val raw = (max - min) * minSpacingPx / heightPx
        val (step, minorDivisions) = niceStep(maxOf(raw, MIN_STEP_PAISE))
        val minorStep = step / minorDivisions
        val major = ArrayList<Double>()
        val minor = ArrayList<Double>()
        var index = floor(min / minorStep).toLong()
        while (index * minorStep <= max && major.size + minor.size < MAX_LINES) {
            val value = index * minorStep
            if (value >= min) {
                if (index % minorDivisions == 0L) major += value else minor += value
            }
            index++
        }
        return AxisTicks(major, minor)
    }

    /** The smallest 1, 2 or 5 times a power of ten at or above [raw], and how many minors it splits into. */
    internal fun niceStep(raw: Double): Pair<Double, Int> {
        val magnitude = 10.0.pow(floor(log10(raw)))
        val fraction = raw / magnitude // in [1, 10)
        return when {
            fraction <= 1.0 -> magnitude to 5
            // A step of 2 splits into quarters, so its minors stay on round values (0.5, 1, 1.5).
            fraction <= 2.0 -> 2 * magnitude to 4
            fraction <= 5.0 -> 5 * magnitude to 5
            else -> 10 * magnitude to 5
        }
    }

    /**
     * Time gridlines across `[fromEpoch, toEpoch]`, majors at least [minSpacingPx] apart at
     * [pxPerMilli]. The finest calendar unit that leaves room is the major, the one below it the
     * minor: hours across a day, days across a week, months across a year.
     */
    fun timeTicks(fromEpoch: Long, toEpoch: Long, pxPerMilli: Double, minSpacingPx: Float): AxisTicks<TimeTick> {
        if (toEpoch <= fromEpoch || pxPerMilli <= 0.0) return AxisTicks(emptyList(), emptyList())
        val majorIndex = UNITS.indexOfFirst { it.nominalMillis * pxPerMilli >= minSpacingPx }
            .let { if (it < 0) UNITS.lastIndex else it }
        val majorUnit = UNITS[majorIndex]
        val minorUnit = UNITS.getOrNull(majorIndex - 1)?.takeIf { it.nominalMillis * pxPerMilli >= MIN_MINOR_PX }

        val majors = marks(fromEpoch, toEpoch, majorUnit)
        val majorSet = majors.toHashSet()
        val formatCache = HashMap<String, SimpleDateFormat>()
        val labelled = majors.map { epoch ->
            val pattern = majorUnit.patternFor(epoch)
            val format = formatCache.getOrPut(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
            TimeTick(epoch, format.format(Date(epoch)))
        }
        val minors = minorUnit?.let { unit ->
            marks(fromEpoch, toEpoch, unit).filterNot { it in majorSet }.map { TimeTick(it, "") }
        }.orEmpty()
        return AxisTicks(labelled, minors)
    }

    private fun marks(fromEpoch: Long, toEpoch: Long, unit: TimeUnit): List<Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = fromEpoch }
        unit.floor(cal)
        val out = ArrayList<Long>()
        while (cal.timeInMillis <= toEpoch && out.size < MAX_LINES) {
            if (cal.timeInMillis >= fromEpoch) out += cal.timeInMillis
            unit.advance(cal)
        }
        return out
    }

    /**
     * One rung of the calendar ladder. Each is aligned to something absolute -- the hour of the day,
     * Monday, the month of the year -- so its marks land in the same places wherever the screen
     * starts, which is what keeps the grid still while the graph slides under it.
     */
    private class TimeUnit(
        val nominalMillis: Double,
        val field: Int,
        val amount: Int,
        val pattern: (Long) -> String
    ) {
        fun patternFor(epoch: Long) = pattern(epoch)

        fun floor(cal: Calendar) {
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            when (field) {
                Calendar.MINUTE -> cal.set(Calendar.MINUTE, cal.get(Calendar.MINUTE) / amount * amount)
                Calendar.HOUR_OF_DAY -> {
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.HOUR_OF_DAY, cal.get(Calendar.HOUR_OF_DAY) / amount * amount)
                }
                Calendar.DAY_OF_MONTH -> {
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                }
                Calendar.WEEK_OF_YEAR -> {
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cal.add(Calendar.DAY_OF_MONTH, -1)
                }
                Calendar.MONTH -> {
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.set(Calendar.MONTH, cal.get(Calendar.MONTH) / amount * amount)
                }
                Calendar.YEAR -> {
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.DAY_OF_YEAR, 1)
                    cal.set(Calendar.YEAR, cal.get(Calendar.YEAR) / amount * amount)
                }
            }
        }

        fun advance(cal: Calendar) {
            if (field == Calendar.WEEK_OF_YEAR) cal.add(Calendar.DAY_OF_MONTH, 7 * amount) else cal.add(field, amount)
        }
    }

    private const val MINUTE = 60_000.0
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private fun isMidnight(epoch: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = epoch }
        return cal.get(Calendar.HOUR_OF_DAY) == 0 && cal.get(Calendar.MINUTE) == 0
    }

    private fun isJanuary(epoch: Long): Boolean =
        Calendar.getInstance().apply { timeInMillis = epoch }.get(Calendar.MONTH) == Calendar.JANUARY

    /** Finest first. Hours name the day at midnight, and months the year in January, so a mark always says where it is. */
    private val UNITS = listOf(
        TimeUnit(15 * MINUTE, Calendar.MINUTE, 15) { if (isMidnight(it)) "d MMM" else "h:mm a" },
        TimeUnit(HOUR, Calendar.HOUR_OF_DAY, 1) { if (isMidnight(it)) "d MMM" else "h a" },
        TimeUnit(3 * HOUR, Calendar.HOUR_OF_DAY, 3) { if (isMidnight(it)) "d MMM" else "h a" },
        TimeUnit(6 * HOUR, Calendar.HOUR_OF_DAY, 6) { if (isMidnight(it)) "d MMM" else "h a" },
        TimeUnit(DAY, Calendar.DAY_OF_MONTH, 1) { "EEE d" },
        TimeUnit(7 * DAY, Calendar.WEEK_OF_YEAR, 1) { "d MMM" },
        TimeUnit(30 * DAY, Calendar.MONTH, 1) { if (isJanuary(it)) "MMM yyyy" else "MMM" },
        TimeUnit(91 * DAY, Calendar.MONTH, 3) { "MMM yy" },
        TimeUnit(365 * DAY, Calendar.YEAR, 1) { "yyyy" },
        TimeUnit(5 * 365 * DAY, Calendar.YEAR, 5) { "yyyy" }
    )

    /** A rupee. */
    private const val MIN_STEP_PAISE = 100.0

    /** Minor lines closer than this are left out: they would only grey the plot. */
    private const val MIN_MINOR_PX = 8.0

    /** However the graph is zoomed, never more lines than this on either axis. */
    private const val MAX_LINES = 400
}
