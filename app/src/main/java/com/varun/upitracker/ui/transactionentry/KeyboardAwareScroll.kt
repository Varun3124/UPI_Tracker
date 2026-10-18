package com.varun.upitracker.ui.transactionentry

import android.graphics.Rect
import android.text.InputType
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.varun.upitracker.ui.theme.dp

/**
 * Keeps the field being typed into -- and, for a name field, its suggestions -- above the keyboard.
 *
 * Needed because the screen is edge-to-edge: `adjustResize` no longer shrinks the window, so the
 * keyboard simply covers the bottom of the form. This reads the keyboard's inset instead, pads the
 * form so every field can scroll clear of it, and scrolls down just far enough when a field is
 * being edited. Never up: a field already in view stays where the user put it.
 *
 * [onKeyboardHidden] runs when the keyboard goes away by itself (Back, or the swipe), which leaves
 * focus behind; the screen uses it to finish the edit as if the field had been left.
 */
class KeyboardAwareScroll(
    private val scroll: ScrollView,
    private val content: View,
    private val onKeyboardHidden: () -> Unit
) {
    private val basePaddingBottom = content.paddingBottom
    private var keyboardVisible = false

    /** How much of the scroll view, measured up from its bottom edge, the keyboard sits over. */
    private var coveredPx = 0

    fun attach() {
        // The root's own listener pads it for the system bars and passes every inset on unconsumed,
        // so this one still sees the keyboard's.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            onInsets(insets)
            insets
        }
        scroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (keyboardVisible && newFocus is EditText) scroll.post { ensureVisible(newFocus) }
        }
    }

    private fun onInsets(insets: WindowInsetsCompat) {
        val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
        val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val location = IntArray(2)
        scroll.getLocationInWindow(location)
        val belowScroll = scroll.rootView.height - (location[1] + scroll.height)
        coveredPx = if (visible) (imeBottom - belowScroll).coerceAtLeast(0) else 0

        // Enough room under the last field for it, and its suggestions, to clear the keyboard.
        val bottom = if (visible) maxOf(basePaddingBottom, coveredPx + scroll.dp(DROPDOWN_ALLOWANCE_DP)) else basePaddingBottom
        if (content.paddingBottom != bottom) content.updatePadding(bottom = bottom)

        val wasVisible = keyboardVisible
        keyboardVisible = visible
        if (visible && !wasVisible) {
            (scroll.findFocus() as? EditText)?.let { field -> scroll.post { ensureVisible(field) } }
        }
        if (!visible && wasVisible) onKeyboardHidden()
    }

    /** Scrolls down only as far as it takes to show [field] and the space its dropdown opens into. */
    private fun ensureVisible(field: View) {
        if (!field.isAttachedToWindow || !keyboardVisible) return
        val rect = Rect()
        field.getDrawingRect(rect)
        scroll.offsetDescendantRectToMyCoords(field, rect)

        val needed = rect.bottom + scroll.dp(allowanceFor(field))
        val visibleBottom = scroll.scrollY + scroll.height - coveredPx
        val shortBy = needed - visibleBottom
        if (shortBy <= 0) return
        // Never at the cost of the field itself: a list too tall to fit below it opens above it.
        val roomAbove = rect.top - scroll.scrollY - scroll.dp(TOP_MARGIN_DP)
        val by = minOf(shortBy, roomAbove.coerceAtLeast(0))
        if (by > 0) scroll.smoothScrollBy(0, by)
    }

    private fun allowanceFor(field: View): Int =
        if (field is AutoCompleteTextView && field.inputType != InputType.TYPE_NULL) DROPDOWN_ALLOWANCE_DP else FIELD_MARGIN_DP

    private companion object {
        /** About three suggestion rows of `simple_dropdown_item_1line`, plus the popup's own padding. */
        const val DROPDOWN_ALLOWANCE_DP = 3 * 48 + 8
        const val FIELD_MARGIN_DP = 8
        const val TOP_MARGIN_DP = 8
    }
}
