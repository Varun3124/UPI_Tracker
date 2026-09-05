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

    private lateinit var periodPills: LinearLayout
    private lateinit var tvRangeLabel: TextView
    private lateinit var btnPrevPeriod: TextView
    private lateinit var btnNextPeriod: TextView
    private lateinit var pieChart: PieChartView
    private lateinit var tvPieTotal: TextView
    private lateinit var tvStatsEmpty: TextView
    private lateinit var legendContainer: LinearLayout
    private lateinit var cardWeekBars: View
    private lateinit var tvPeakDay: TextView
    private lateinit var weekBars: StackedBarChartView

    private val pills = mutableListOf<Pair<StatsPeriod, TextView>>()

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
        buildPills()

        viewModel = ViewModelProvider(
            this,
            StatisticsViewModelFactory(applicationContext)
        )[StatisticsViewModel::class.java]

        viewModel.uiState.observe(this) { render(it) }
        viewModel.load()
    }

    private fun bindViews() {
        periodPills = findViewById(R.id.periodPills)
        tvRangeLabel = findViewById(R.id.tvRangeLabel)
        btnPrevPeriod = findViewById(R.id.btnPrevPeriod)
        btnNextPeriod = findViewById(R.id.btnNextPeriod)
        pieChart = findViewById(R.id.pieChart)
        tvPieTotal = findViewById(R.id.tvPieTotal)
        tvStatsEmpty = findViewById(R.id.tvStatsEmpty)
        legendContainer = findViewById(R.id.legendContainer)
        cardWeekBars = findViewById(R.id.cardWeekBars)
        tvPeakDay = findViewById(R.id.tvPeakDay)
        weekBars = findViewById(R.id.weekBars)

        findViewById<TextView>(R.id.btnBackStats).setOnClickListener { finish() }
        btnPrevPeriod.setOnClickListener { viewModel.step(-1) }
        btnNextPeriod.setOnClickListener { viewModel.step(1) }
    }

    private fun buildPills() {
        val labels = listOf(
            StatsPeriod.DAILY to "Day",
            StatsPeriod.WEEKLY to "Week",
            StatsPeriod.MONTHLY to "Month",
            StatsPeriod.QUARTERLY to "Quarter",
            StatsPeriod.ALL_TIME to "All time",
            StatsPeriod.CUSTOM to "Custom"
        )
        labels.forEach { (period, label) ->
            val pill = TextView(this).apply {
                text = label
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (period == StatsPeriod.CUSTOM) showRangePicker() else viewModel.selectPeriod(period)
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            periodPills.addView(pill, params)
            pills += period to pill
        }
    }

    private fun render(state: StatisticsUiState) {
        pills.forEach { (period, view) -> stylePill(view, period == state.period) }
        tvRangeLabel.text = rangeLabel(state)

        val shiftable = state.period.isShiftable
        btnPrevPeriod.visibility = if (shiftable) View.VISIBLE else View.INVISIBLE
        btnNextPeriod.visibility = btnPrevPeriod.visibility
        btnNextPeriod.isEnabled = state.canGoForward
        btnNextPeriod.alpha = if (state.canGoForward) 1f else DISABLED_ALPHA

        val slices = state.breakdown.slices
        val total = state.breakdown.totalPaise
        pieChart.setSlices(slices.map { it.paise }, slices.map { it.color })
        tvPieTotal.text = formatRupees(total)
        tvStatsEmpty.visibility = if (slices.isEmpty()) View.VISIBLE else View.GONE
        buildLegend(slices, total)

        val showBars = state.period == StatsPeriod.WEEKLY && state.breakdown.days.isNotEmpty()
        cardWeekBars.visibility = if (showBars) View.VISIBLE else View.GONE
        if (showBars) {
            val days = state.breakdown.days
            weekBars.setColumns(days.map { it.label }, days.map { it.segments }, slices.map { it.color })
            val peak = days.maxOf { it.totalPaise }
            tvPeakDay.text = if (peak > 0L) "Busiest day ${formatRupees(peak)}" else "Nothing spent this week"
        }
    }

    private fun buildLegend(slices: List<CategorySlice>, totalPaise: Long) {
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

    /** The app has no selector drawables; this is the same pill look All Transactions uses. */
    private fun stylePill(view: TextView, active: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(if (active) Color.parseColor("#006064") else Color.parseColor("#EEEEEE"))
            setStroke(dp(1), if (active) Color.parseColor("#006064") else Color.parseColor("#DDDDDD"))
        }
        view.setTextColor(if (active) Color.parseColor("#FFFFFF") else Color.parseColor("#212121"))
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private companion object {
        const val DISABLED_ALPHA = 0.3f
    }
}
