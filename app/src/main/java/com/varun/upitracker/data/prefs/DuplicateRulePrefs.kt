package com.varun.upitracker.data.prefs

import android.content.Context
import com.varun.upitracker.domain.dedup.DuplicateRules

/** Settings > Duplicate check: how loosely imports look for a row that may already be recorded. */
class DuplicateRulePrefs(context: Context) {

    private val prefs = AppPrefs.of(context)

    fun get(): DuplicateRules {
        val defaults = DuplicateRules()
        val days = prefs.getInt(DAYS, defaults.daysEitherSide)
        val tolerance = prefs.getLong(TOLERANCE, defaults.amountTolerancePaise)
        // Numbers a restore brought in that no longer hold read as the defaults, the switches as stored.
        val numbersOk = DuplicateRules.problemWith(days, tolerance) == null
        return DuplicateRules(
            daysEitherSide = if (numbersOk) days else defaults.daysEitherSide,
            amountTolerancePaise = if (numbersOk) tolerance else defaults.amountTolerancePaise,
            sameCounterpartyOnly = prefs.getBoolean(SAME_COUNTERPARTY, defaults.sameCounterpartyOnly),
            sameAccountOnly = prefs.getBoolean(SAME_ACCOUNT, defaults.sameAccountOnly),
            skipOtherReferences = prefs.getBoolean(SKIP_OTHER_REFERENCES, defaults.skipOtherReferences)
        )
    }

    /** Null once kept; otherwise why not, fit to show. */
    fun set(rules: DuplicateRules): String? {
        DuplicateRules.problemWith(rules.daysEitherSide, rules.amountTolerancePaise)?.let { return it }
        prefs.edit()
            .putInt(DAYS, rules.daysEitherSide)
            .putLong(TOLERANCE, rules.amountTolerancePaise)
            .putBoolean(SAME_COUNTERPARTY, rules.sameCounterpartyOnly)
            .putBoolean(SAME_ACCOUNT, rules.sameAccountOnly)
            .putBoolean(SKIP_OTHER_REFERENCES, rules.skipOtherReferences)
            .apply()
        return null
    }

    private companion object {
        const val DAYS = "dedup_days_either_side"
        const val TOLERANCE = "dedup_amount_tolerance_paise"
        const val SAME_COUNTERPARTY = "dedup_same_counterparty_only"
        const val SAME_ACCOUNT = "dedup_same_account_only"
        const val SKIP_OTHER_REFERENCES = "dedup_skip_other_references"
    }
}
