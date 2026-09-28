package com.varun.upitracker.ui

import android.app.DatePickerDialog
import android.view.Gravity
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.varun.upitracker.R
import com.varun.upitracker.domain.statistics.ListWindow
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The month button that every transaction list is scoped by.
 *
 * Three gestures on one control: a tap picks a month, a long-press offers all time or a hand-picked
 * range, and a vertical drag steps a month at a time. All Transactions had all of it inline; a
 * friend's page needs the same thing, and the gestures are far too fiddly to keep two copies of.
 *
 * @param onPicked a window the caller should load.
 */
class MonthRangeControl(
    private val activity: AppCompatActivity,
    private val button: TextView,
    private val onPicked: (ListWindow) -> Unit
) {

    private val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val shortFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    /** The window last rendered, which the pickers open on. */
    private var current: ListWindow = ListWindow.currentMonth()

    fun attach() {
        button.setOnClickListener { showMonthPicker() }
        button.setOnLongClickListener {
            showRangeChoiceMenu()
            true
        }
        wireSwipeGesture()
    }

    fun render(window: ListWindow) {
        current = window
        button.text = label(window)
    }

    fun label(window: ListWindow): String = when {
        window.isAllTime -> "All time"
        !window.isCustom -> monthFmt.format(Date(window.startEpoch))
        // The stored end is exclusive; show the inclusive day the user actually picked.
        else -> {
            val first = shortFmt.format(Date(window.startEpoch))
            val last = shortFmt.format(Date(window.lastMillis))
            if (first == last) first else "$first - $last"
        }
    }

    /**
     * Any vertical drag changes the month on finger-up, whatever its length or speed: up for next,
     * down for previous. Only [ViewConfiguration.getScaledTouchSlop] worth of movement is required,
     * purely to tell a drag apart from a stationary tap; there is no minimum distance or fling
     * velocity beyond that.
     *
     * A raw touch listener rather than a [android.view.GestureDetector] because a fling detector
     * requires velocity and is built to reject slow drags, which is exactly what this has to accept.
     * Once slop is crossed an ACTION_CANCEL is dispatched into the button's own touch handling so its
     * pressed state clears and no click fires alongside the swipe; movement that stays under slop is
     * left untouched so tap and long-press keep working.
     */
    private fun wireSwipeGesture() {
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        var startY = 0f
        var isDragging = false

        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging && kotlin.math.abs(event.rawY - startY) > touchSlop) {
                        isDragging = true
                        val cancel = MotionEvent.obtain(event).apply { action = ACTION_CANCEL }
                        view.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val movedUp = event.rawY - startY < 0
                        onPicked(current.shiftedByMonths(if (movedUp) 1 else -1, System.currentTimeMillis()))
                    }
                    val wasDragging = isDragging
                    isDragging = false
                    wasDragging
                }
                ACTION_CANCEL -> {
                    isDragging = false
                    false
                }
                else -> false
            }
        }
    }

    /** Long-press: "All time" jumps straight there; otherwise pick a range. */
    private fun showRangeChoiceMenu() {
        AlertDialog.Builder(activity)
            .setItems(arrayOf("All time", "Custom date range")) { _, which ->
                if (which == 0) onPicked(ListWindow.allTime()) else showRangePicker()
            }
            .show()
    }

    /** Pick From, then To. Both ends inclusive. */
    private fun showRangePicker() {
        pickDate("Range starts", current.startEpoch) { fromEpoch ->
            pickDate("Range ends", maxOf(fromEpoch, current.lastMillis)) { toEpoch ->
                if (toEpoch < fromEpoch) {
                    Toast.makeText(activity, "End date is before the start date", Toast.LENGTH_SHORT).show()
                    return@pickDate
                }
                onPicked(ListWindow.custom(fromEpoch, toEpoch))
            }
        }
    }

    private fun pickDate(title: String, initialEpoch: Long, onDate: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = initialEpoch }
        DatePickerDialog(
            activity,
            { _, year, month, day ->
                onDate(
                    Calendar.getInstance().apply {
                        set(year, month, day, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                )
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply { setTitle(title) }.show()
    }

    private fun showMonthPicker() {
        // All time has no month to open on, so the wheels start on this one.
        val anchor = if (current.isAllTime) System.currentTimeMillis() else current.startEpoch
        val selected = Calendar.getInstance().apply { timeInMillis = anchor }
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        val months = DateFormatSymbols.getInstance(Locale.getDefault()).months.take(12).toTypedArray()

        val monthPicker = NumberPicker(activity).apply {
            minValue = 0
            maxValue = 11
            displayedValues = months
            value = selected.get(Calendar.MONTH)
            wrapSelectorWheel = true
        }
        val yearPicker = NumberPicker(activity).apply {
            minValue = 2000
            maxValue = thisYear + 5
            value = selected.get(Calendar.YEAR).coerceIn(minValue, maxValue)
            wrapSelectorWheel = false
        }
        val pickerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val side = resources.getDimensionPixelSize(R.dimen.space_l)
            setPadding(side, resources.getDimensionPixelSize(R.dimen.space_m), side, 0)
            addView(monthPicker)
            addView(yearPicker)
        }

        AlertDialog.Builder(activity)
            .setTitle("Select month")
            .setView(pickerRow)
            .setPositiveButton("Show") { _, _ ->
                onPicked(ListWindow.month(yearPicker.value, monthPicker.value))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
