package com.varun.upitracker.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * Every amount the user reads is formatted here.
 *
 * Deliberately free of any `android.*` import: `TransactionValidator` builds user-facing strings too
 * and is covered by plain JVM unit tests, so a formatter those tests can call cannot live in the
 * `ui` package next to `android.graphics.Color`.
 *
 * Amounts arrive as a [Long] count of paise throughout the app -- never a Double, never a
 * BigDecimal -- so every entry point here takes paise and divides internally.
 */
object AmountFormat {

    const val SYMBOL = "\u20B9"

    /**
     * Below ten thousand rupees the app prints no separators at all: `383`, `4916`. At and above it,
     * Indian grouping applies: `10,000`, `1,50,000`, `1,20,00,000`.
     *
     * Worth knowing why the threshold looks like it does nothing in the 10,000-99,999 band: Indian
     * and Western grouping only diverge from one lakh upward, so the threshold is really a choice
     * about whether four-digit amounts carry a comma, and here they do not.
     */
    const val GROUPING_THRESHOLD_RUPEES = 10_000L

    /**
     * Grouping is done by hand rather than with a `#,##,##0` DecimalFormat pattern, because
     * `java.text.DecimalFormat` keeps only one grouping size and applies it uniformly -- that pattern
     * yields `100,000` on the JVM. Android's DecimalFormat is ICU-backed and *does* honour it, which
     * is the worse failure of the two: the unit tests would pass while the device disagreed with
     * them. `NumberFormat.getIntegerInstance(Locale("en","IN"))`, which this code used to call, has
     * the same split -- some Android versions ship plain three-digit grouping for `en-IN`.
     *
     * DecimalFormat is still used for the fractional part, where a single grouping size is all that
     * is wanted. It is not thread-safe and a few of these calls happen off the main thread, hence the
     * ThreadLocal.
     */
    private val plainTwoDp = ThreadLocal.withInitial {
        DecimalFormat("0.00", DecimalFormatSymbols(Locale.ROOT))
    }

    /** `1,50,000` / `4916`. Unsigned, no symbol -- the grouping rule on its own. */
    fun groupIndian(rupees: Long): String {
        val magnitude = abs(rupees)
        if (magnitude < GROUPING_THRESHOLD_RUPEES) return magnitude.toString()

        // The last three digits are one group; everything to their left is grouped in twos.
        val digits = magnitude.toString()
        val head = digits.substring(0, digits.length - 3)
        val tail = digits.substring(digits.length - 3)

        val out = StringBuilder()
        var cut = head.length
        while (cut > 0) {
            val start = maxOf(0, cut - 2)
            if (out.isNotEmpty()) out.insert(0, ',')
            out.insert(0, head, start, cut)
            cut = start
        }
        return out.append(',').append(tail).toString()
    }

    /** The default: signed, symbol-prefixed, whole rupees. */
    fun rupees(paise: Long): String = sign(paise) + SYMBOL + groupIndian(paise / 100)

    /**
     * Signed and symbol-prefixed, keeping the paise only when there are any. Used where an amount has
     * to be exact rather than tidy -- validation messages, account balances, the entry screen's
     * running totals.
     */
    fun rupeesExact(paise: Long): String = sign(paise) + SYMBOL + exact(abs(paise))

    /** As [rupeesExact] but without the symbol, for strings that supply their own. */
    fun plain(paise: Long): String = sign(paise) + exact(abs(paise))

    /**
     * For an EditText, and only for an EditText: never grouped, so `toDoubleOrNull()` still parses it
     * on the way back. Sharing one formatter between a label and an input field is how commas end up
     * in a field and a save silently turns into a no-op.
     */
    fun forInput(paise: Long): String {
        val magnitude = abs(paise)
        return if (magnitude % 100 == 0L) (magnitude / 100).toString()
        else plainTwoDp.get()!!.format(magnitude / 100.0)
    }

    /**
     * Chart axis ticks, where lakh commas are too wide to fit beside a plot: `12k`, `1.5L`, `2.4Cr`.
     * One decimal only while the leading figure is a single digit, so labels keep a similar width as
     * the axis rescales.
     */
    fun axis(paise: Long): String {
        val rupees = paise / 100
        val magnitude = abs(rupees)
        val body = when {
            magnitude < 1_000L -> magnitude.toString()
            magnitude < 100_000L -> compact(magnitude, 1_000.0, "k")
            magnitude < 10_000_000L -> compact(magnitude, 100_000.0, "L")
            else -> compact(magnitude, 10_000_000.0, "Cr")
        }
        return sign(rupees) + body
    }

    private fun compact(magnitude: Long, divisor: Double, suffix: String): String {
        val scaled = magnitude / divisor
        return if (scaled < 10.0 && scaled != scaled.toLong().toDouble()) {
            String.format(Locale.ROOT, "%.1f", scaled) + suffix
        } else {
            scaled.toLong().toString() + suffix
        }
    }

    /** Whole rupees when the amount is round, two decimals when it is not. Grouped either way. */
    private fun exact(magnitude: Long): String {
        val whole = groupIndian(magnitude / 100)
        if (magnitude % 100 == 0L) return whole
        // Format the fractional part on its own so the grouping rule above still owns the integer
        // part; "0.50".removePrefix("0") leaves ".50".
        return whole + plainTwoDp.get()!!.format(magnitude % 100 / 100.0).removePrefix("0")
    }

    /** The sign goes before the symbol, so a negative balance reads `-<symbol>500`, not `<symbol>-500`. */
    private fun sign(value: Long): String = if (value < 0) "-" else ""
}
