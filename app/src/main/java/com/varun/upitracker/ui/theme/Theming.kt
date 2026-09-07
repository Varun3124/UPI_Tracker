package com.varun.upitracker.ui.theme

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.varun.upitracker.R
import com.google.android.material.R as MaterialR

/**
 * Reading colours and sizes off the theme, in the four or five shapes the app actually needs.
 *
 * Before this existed every screen wrote its own `Color.parseColor("#212121")` and its own private
 * `dp()`; the point of routing through here is that a colour can only come from the theme, so light
 * and dark stay in step by construction.
 */

/**
 * Magenta is the fallback on purpose: an attribute that was never added to the theme should be
 * loud on screen rather than quietly resolving to black and looking almost right.
 */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr, Color.MAGENTA)

@ColorInt
fun View.themeColor(@AttrRes attr: Int): Int = context.themeColor(attr)

fun Context.dp(value: Int): Int = dpF(value.toFloat()).toInt()

fun Context.dpF(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.spF(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

fun View.dp(value: Int): Int = context.dp(value)

fun View.dpF(value: Float): Float = context.dpF(value)

fun View.spF(value: Float): Float = context.spF(value)

/**
 * Pads a root view by the real system bar insets, on top of whatever padding the layout already
 * declares.
 *
 * Not optional housekeeping: the app targets SDK 36, and from Android 15 the system draws every app
 * edge-to-edge whether it asks to or not. Without this a screen renders its top bar underneath the
 * status bar. Only the dashboard handled insets before, so every other screen was already wrong on
 * new devices.
 *
 * @param top pass false when a screen paints its own colour behind the status bar.
 */
fun View.padForSystemBars(top: Boolean = true, bottom: Boolean = true) {
    val original = Insets.of(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(
            original.left + bars.left,
            original.top + if (top) bars.top else 0,
            original.right + bars.right,
            original.bottom + if (bottom) bars.bottom else 0
        )
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)
}

/** The whole edge-to-edge setup for a screen whose root is `@id/main`, which is all of them. */
fun Activity.padRootForSystemBars(rootId: Int, top: Boolean = true, bottom: Boolean = true) {
    findViewById<View>(rootId)?.padForSystemBars(top, bottom)
}

/**
 * Every theme colour the app reads, under one name.
 *
 * The indirection earns its keep because the ids come from two different R classes and there is no
 * way to tell which from the call site: `appPositive` is the app's own attribute, `colorPrimary`
 * belongs to the Material library, and with non-transitive R classes (the AGP 8+ default) the app's
 * `R.attr` does not contain the latter. Rather than sprinkle `MaterialR.` around, the split is
 * settled once here.
 */
object ThemeAttr {

    // --- Material roles -------------------------------------------------------------------
    val primary = MaterialR.attr.colorPrimary
    val onPrimary = MaterialR.attr.colorOnPrimary
    val primaryContainer = MaterialR.attr.colorPrimaryContainer
    val onPrimaryContainer = MaterialR.attr.colorOnPrimaryContainer
    val secondary = MaterialR.attr.colorSecondary
    val onSecondary = MaterialR.attr.colorOnSecondary
    val secondaryContainer = MaterialR.attr.colorSecondaryContainer
    val onSecondaryContainer = MaterialR.attr.colorOnSecondaryContainer
    val surface = MaterialR.attr.colorSurface
    val onSurface = MaterialR.attr.colorOnSurface
    val surfaceVariant = MaterialR.attr.colorSurfaceVariant
    val onSurfaceVariant = MaterialR.attr.colorOnSurfaceVariant
    val outline = MaterialR.attr.colorOutline
    val outlineVariant = MaterialR.attr.colorOutlineVariant
    val error = MaterialR.attr.colorError
    val background = android.R.attr.colorBackground

    // --- App roles ------------------------------------------------------------------------
    val positive = R.attr.appPositive
    val negative = R.attr.appNegative
    val warning = R.attr.appWarning
    val amountNeutral = R.attr.appAmountNeutral
    val positiveContainer = R.attr.appPositiveContainer
    val onPositiveContainer = R.attr.appOnPositiveContainer
    val negativeContainer = R.attr.appNegativeContainer
    val onNegativeContainer = R.attr.appOnNegativeContainer
    val textMuted = R.attr.appTextMuted

    val avatarMe = R.attr.appAvatarMe
    val onAvatarMe = R.attr.appOnAvatarMe
    val avatarFriend = R.attr.appAvatarFriend
    val onAvatarFriend = R.attr.appOnAvatarFriend
    val avatarMerchant = R.attr.appAvatarMerchant
    val onAvatarMerchant = R.attr.appOnAvatarMerchant

    val chartGrid = R.attr.appChartGrid
    val chartZeroLine = R.attr.appChartZeroLine
    val chartAxisLabel = R.attr.appChartAxisLabel
    val chartTrack = R.attr.appChartTrack
    val chartNeutral = R.attr.appChartNeutral
}
