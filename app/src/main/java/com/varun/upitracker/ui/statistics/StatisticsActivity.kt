package com.varun.upitracker.ui.statistics

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.varun.upitracker.R
import com.varun.upitracker.domain.statistics.CategorySlice
import com.varun.upitracker.domain.statistics.StatisticsPeriods
import com.varun.upitracker.domain.statistics.StatsPeriod
import com.varun.upitracker.ui.formatRupees
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class StatisticsActivity : AppCompatActivity() {

    private lateinit var viewModel: StatisticsViewModel

    private lateinit var btnPickPeriod: TextView
    private lateinit var tvRangeLabel: TextView
    private lateinit var btnPrevPeriod: TextView
    private lateinit var btnNextPeriod: TextView
    private lateinit var pieChart: PieChartView
    private lateinit var tvPieTotal: TextView
    private lateinit var tvStatsEmpty: TextView
    private lateinit var tvCategoryCardTitle: TextView
    private lateinit var btnClearDrill: TextView
    private lateinit var legendContainer: LinearLayout
    private lateinit var cardWeekBars: View
    private lateinit var tvPeakDay: TextView
    private lateinit var weekBars: StackedBarChartView
    private lateinit var swipeContainer: SwipeableFrameLayout

    private val dayFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val shortFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statistics)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()

        viewModel = ViewModelProvider(
            this,
            StatisticsViewModelFactory(applicationContext)
        )[StatisticsViewModel::class.java]

        viewModel.uiState.observe(this) { render(it) }
        viewModel.load()
    }

    private fun bindViews() {
        btnPickPeriod = findViewById(R.id.btnPickPeriod)
        tvRangeLabel = findViewById(R.id.tvRangeLabel)
        btnPrevPeriod = findViewById(R.id.btnPrevPeriod)
        btnNextPeriod = findViewById(R.id.btnNextPeriod)
        pieChart = findViewById(R.id.pieChart)
        tvPieTotal = findViewById(R.id.tvPieTotal)
        tvStatsEmpty = findViewById(R.id.tvStatsEmpty)
        tvCategoryCardTitle = findViewById(R.id.tvCategoryCardTitle)
        btnClearDrill = findViewById(R.id.btnClearDrill)
        legendContainer = findViewById(R.id.legendContainer)
        cardWeekBars = findViewById(R.id.cardWeekBars)
        tvPeakDay = findViewById(R.id.tvPeakDay)
        weekBars = findViewById(R.id.weekBars)
        swipeContainer = findViewById(R.id.swipeContainer)
        btnClearDrill.setOnClickListener { viewModel.clearDrill() }
        pieChart.onSliceTapped = { index ->
            // Drilling only makes sense one level deep; a tap inside the merchant pie is inert.
            val state = viewModel.uiState.value
            if (state != null && state.drilledCategory == null) {
                state.breakdown.slices.getOrNull(index)?.let { viewModel.drillInto(it) }
            }
        }
        weekBars.onDayTapped = { index ->
            viewModel.uiState.value?.breakdown?.days?.getOrNull(index)
                ?.let { viewModel.selectDay(it.dayStartEpoch) }
        }
        swipeContainer.onSwipe = { direction -> viewModel.step(direction) }

        btnPickPeriod.setOnClickListener { showPeriodMenu() }
        findViewById<TextView>(R.id.btnBackStats).setOnClickListener { finish() }
        btnPrevPeriod.setOnClickListener { viewModel.step(-1) }
        btnNextPeriod.setOnClickListener { viewModel.step(1) }
    }

    /**
     * One tap, one list. An AlertDialog rather than a PopupMenu because Custom has to chain into
     * two DatePickerDialogs, which is the shape showRangeChoiceMenu already uses elsewhere.
     */
    private fun showPeriodMenu() {
        val options = PERIOD_LABELS.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Show")
            .setItems(options) { _, which ->
                val period = PERIOD_LABELS[which].first
                if (period == StatsPeriod.CUSTOM) showRangePicker() else viewModel.selectPeriod(period)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun render(state: StatisticsUiState) {
        // The app has no chevron asset; every other glyph in it is a literal character.
        btnPickPeriod.text = "${periodLabel(state.period)}  \u25BE"
        tvRangeLabel.text = rangeLabel(state)

        val shiftable = state.period.isShiftable
        btnPrevPeriod.visibility = if (shiftable) View.VISIBLE else View.INVISIBLE
        btnNextPeriod.visibility = btnPrevPeriod.visibility
        btnNextPeriod.isEnabled = state.canGoForward
        btnNextPeriod.alpha = if (state.canGoForward) 1f else DISABLED_ALPHA
        swipeContainer.isSwipeEnabled = shiftable
        swipeContainer.canSwipeForward = state.canGoForward

        val drilled = state.drilledCategory
        val slices = if (drilled != null) state.payeeSlices else state.breakdown.slices
        val total = slices.sumOf { it.paise }

        tvCategoryCardTitle.text = if (drilled != null) "BY MERCHANT - ${drilled.name}" else "BY CATEGORY"
        btnClearDrill.visibility = if (drilled != null) View.VISIBLE else View.GONE

        pieChart.setSlices(slices.map { it.paise }, slices.map { it.color })
        tvPieTotal.text = formatRupees(total)
        tvStatsEmpty.visibility = if (slices.isEmpty()) View.VISIBLE else View.GONE
        tvStatsEmpty.text = if (drilled != null) {
            "Nothing under ${drilled.name} in this period"
        } else {
            "No spending in this period"
        }
        buildLegend(slices, total, drillable = drilled == null)

        renderWeekBars(state, drilled)
    }

    /**
     * While drilled, the bars narrow to that one category using the column already folded for it --
     * no extra query, and the same numbers the pie was built from.
     */
    private fun renderWeekBars(state: StatisticsUiState, drilled: CategorySlice?) {
        val days = state.breakdown.days
        val showBars = state.period == StatsPeriod.WEEKLY && days.isNotEmpty()
        cardWeekBars.visibility = if (showBars) View.VISIBLE else View.GONE
        if (!showBars) return

        val categoryIndex = drilled?.let { target ->
            state.breakdown.slices.indexOfFirst { it.categoryId == target.categoryId }
        } ?: -1

        val columns: List<List<Long>>
        val colors: List<Int>
        if (drilled != null) {
            // Absent from this period: draw an empty week rather than the whole breakdown.
            columns = days.map { day -> listOf(day.segments.getOrElse(categoryIndex) { 0L }) }
            colors = listOf(drilled.color)
        } else {
            columns = days.map { it.segments }
            colors = state.breakdown.slices.map { it.color }
        }

        // Only the ends carry a date, so the week's span reads without labelling every column.
        val dates = days.indices.map { index ->
            if (index == 0 || index == days.lastIndex) shortFmt.format(Date(days[index].dayStartEpoch))
            else null
        }
        weekBars.setColumns(days.map { it.label }, dates, columns, colors)

        val peak = columns.maxOf { it.sum() }
        tvPeakDay.text = if (peak > 0L) "Busiest day ${formatRupees(peak)}" else "Nothing spent this week"
    }

    private fun buildLegend(slices: List<CategorySlice>, totalPaise: Long, drillable: Boolean) {
        legendContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        slices.forEach { slice ->
            val row = inflater.inflate(R.layout.item_stat_legend, legendContainer, false)
            row.findViewById<View>(R.id.viewLegendSwatch).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(slice.color)
            }
            row.findViewById<TextView>(R.id.tvLegendName).text = slice.name
            row.findViewById<TextView>(R.id.tvLegendAmount).text = formatRupees(slice.paise)
            row.findViewById<TextView>(R.id.tvLegendPercent).text =
                if (totalPaise > 0L) "${(slice.paise * 100.0 / totalPaise).toInt()}%" else ""
            if (drillable) {
                row.isClickable = true
                row.setOnClickListener { viewModel.drillInto(slice) }
            }
            legendContainer.addView(row)
        }
    }

    private fun rangeLabel(state: StatisticsUiState): String = when (state.period) {
        StatsPeriod.ALL_TIME -> "All time"
        StatsPeriod.DAILY -> dayFmt.format(Date(state.range.toInclusive))
        StatsPeriod.MONTHLY -> monthFmt.format(Date(state.range.toInclusive))
        StatsPeriod.WEEKLY, StatsPeriod.QUARTERLY, StatsPeriod.CUSTOM -> {
            // fromExclusive is one millisecond before the first day, so add it back before
            // formatting or a week would read as starting the previous Sunday.
            val first = Date(state.range.fromExclusive + 1)
            "${shortFmt.format(first)} - ${shortFmt.format(Date(state.range.toInclusive))}"
        }
    }

    /** Pick From, then To. Both ends inclusive, matching the All Transactions range picker. */
    private fun showRangePicker() {
        val anchor = viewModel.uiState.value?.range?.toInclusive ?: System.currentTimeMillis()
        pickDate("Range starts", StatisticsPeriods.startOfMonth(anchor)) { fromEpoch ->
            pickDate("Range ends", maxOf(fromEpoch, anchor)) { toEpoch ->
                if (toEpoch < fromEpoch) {
                    Toast.makeText(this, "End date is before the start date", Toast.LENGTH_SHORT).show()
                    return@pickDate
                }
                viewModel.selectCustomRange(fromEpoch, toEpoch)
            }
        }
    }

    private fun pickDate(title: String, initialEpoch: Long, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = initialEpoch }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                onPicked(
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

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun periodLabel(period: StatsPeriod): String =
        PERIOD_LABELS.first { it.first == period }.second

    private companion object {
        const val DISABLED_ALPHA = 0.3f

        /** Menu order, and the source of the button's label. */
        val PERIOD_LABELS = listOf(
            StatsPeriod.DAILY to "Day",
            StatsPeriod.WEEKLY to "Week",
            StatsPeriod.MONTHLY to "Month",
            StatsPeriod.QUARTERLY to "Quarter",
            StatsPeriod.ALL_TIME to "All time",
            StatsPeriod.CUSTOM to "Custom"
        )
    }
}
