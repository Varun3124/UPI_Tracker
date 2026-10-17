package com.varun.upitracker.data.prefs

import android.content.Context
import com.varun.upitracker.domain.iou.IouThresholds

/**
 * Where the two [IouThresholds] lines are kept: the app's preferences, so a backup carries them like
 * any other preference. Unset reads as the defaults, ₹10 settled and ₹100 insignificant.
 */
class IouThresholdPrefs(context: Context) {

    private val prefs = AppPrefs.of(context)

    fun get(): IouThresholds {
        val settled = prefs.getLong(SETTLED_BELOW, IouThresholds.DEFAULT_SETTLED_BELOW_PAISE)
        val insignificant = prefs.getLong(INSIGNIFICANT_BELOW, IouThresholds.DEFAULT_INSIGNIFICANT_BELOW_PAISE)
        // A pair written by hand or by a restore that no longer holds together reads as the defaults.
        return if (IouThresholds.problemWith(settled, insignificant) == null) {
            IouThresholds(settled, insignificant)
        } else {
            IouThresholds()
        }
    }

    /** Null once kept; otherwise why not, fit to show. */
    fun set(thresholds: IouThresholds): String? {
        IouThresholds.problemWith(thresholds.settledBelowPaise, thresholds.insignificantBelowPaise)?.let { return it }
        prefs.edit()
            .putLong(SETTLED_BELOW, thresholds.settledBelowPaise)
            .putLong(INSIGNIFICANT_BELOW, thresholds.insignificantBelowPaise)
            .apply()
        return null
    }

    private companion object {
        const val SETTLED_BELOW = "iou_settled_below_paise"
        const val INSIGNIFICANT_BELOW = "iou_insignificant_below_paise"
    }
}
