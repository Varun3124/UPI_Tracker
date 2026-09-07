package com.varun.upitracker.ui.statistics

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.varun.upitracker.R
import com.varun.upitracker.domain.statistics.CategorySlice
import com.varun.upitracker.domain.statistics.StatisticsPeriods
import com.varun.upitracker.database.entity.Account
import com.varun.upitracker.domain.statistics.AccountScope
import com.varun.upitracker.domain.statistics.StatsPeriod
import com.varun.upitracker.domain.statistics.TrendBucket
import com.varun.upitracker.domain.statistics.TrendsBuckets
import com.varun.upitracker.ui.formatRupees
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.varun.upitracker.ui.theme.ChartColors
import android.widget.ImageButton
import com.varun.upitracker.ui.theme.padRootForSystemBars
import android.content.res.ColorStateList
import androidx.core.widget.TextViewCompat
import com.varun.upitracker.ui.theme.themeColor
import com.varun.upitracker.ui.theme.ThemeAttr
import com.varun.upitracker.ui.theme.dp

class StatisticsActivity : AppCompatActivity() {

    private lateinit var viewModel: StatisticsViewModel

    private lateinit var btnPickPeriod: TextView
    private lateinit var tvRangeLabel: TextView
    private lateinit var btnPrevPeriod: ImageButton
    private lateinit var btnNextPeriod: ImageButton
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
    private lateinit var trendsScroll: View
    private lateinit var btnSectionCategories: TextView
    private lateinit var btnSectionTrends: TextView
    private lateinit var balanceLine: LineChartView
    private lateinit var tvBalanceLatest: TextView
    private lateinit var tvBalanceChange: TextView
    private lateinit var tvTrendsEmpty: TextView
    private lateinit var flowChart: IncomeExpenseChartView
    private lateinit var tvFlowSummary: TextView
    private lateinit var cardFlowTrend: View
    private lateinit var btnPickScope: TextView

    private val dayFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val shortFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statistics)

        padRootForSystemBars(R.id.main)

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
        trendsScroll = findViewById(R.id.trendsScroll)
        btnSectionCategories = findViewById(R.id.btnSectionCategories)
        btnSectionTrends = findViewById(R.id.btnSectionTrends)
        balanceLine = findViewById(R.id.balanceLine)
        tvBalanceLatest = findViewById(R.id.tvBalanceLatest)
        tvBalanceChange = findViewById(R.id.tvBalanceChange)
        tvTrendsEmpty = findViewById(R.id.tvTrendsEmpty)
        flowChart = findViewById(R.id.flowChart)
        tvFlowSummary = findViewById(R.id.tvFlowSummary)
        cardFlowTrend = findViewById(R.id.cardFlowTrend)
        btnPickScope = findViewById(R.id.btnPickScope)
        btnPickScope.setOnClickListener { showScopeMenu() }
        // Both charts drive the same window, which is what keeps them in step: whichever one the
        // finger is on, the other redraws from the state the first one moved.
        listOf(balanceLine, flowChart).forEach { chart ->
            chart.onPanStart = { viewModel.beginPan() }
            chart.onPan = { buckets -> viewModel.panBy(buckets) }
            chart.onPanEnd = { viewModel.endPan() }
        }
        btnSectionCategories.setOnClickListener { viewModel.selectSection(StatsSection.CATEGORIES) }
        btnSectionTrends.setOnClickListener { viewModel.selectSection(StatsSection.TRENDS) }
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
        findViewById<ImageButton>(R.id.btnBackStats).setOnClickListener { finish() }
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
        renderSection(state)
        if (state.section == StatsSection.TRENDS) renderTrends(state.trends)

        val drilled = state.drilledCategory
        val slices = if (drilled != null) state.payeeSlices else state.breakdown.slices
        val total = slices.sumOf { it.paise }

        tvCategoryCardTitle.text = if (drilled != null) "BY MERCHANT - ${drilled.name}" else "BY CATEGORY"
        btnClearDrill.visibility = if (drilled != null) View.VISIBLE else View.GONE

        pieChart.setSlices(slices.map { it.paise }, slices.map { categoryColor(it) })
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
     * The two sections share the header, the period control and the range label -- only the content
     * and the stepping controls differ.
     *
     * Trends steps by panning rather than by the chevrons, so those hide there. The range label
     * stays: Trends has a visible window of its own, and this is the only thing that names it.
     */
    private fun renderSection(state: StatisticsUiState) {
        val categories = state.section == StatsSection.CATEGORIES
        btnSectionCategories.isSelected = categories
        btnSectionTrends.isSelected = !categories

        swipeContainer.visibility = if (categories) View.VISIBLE else View.GONE
        trendsScroll.visibility = if (categories) View.GONE else View.VISIBLE
        btnPickScope.visibility = if (categories) View.GONE else View.VISIBLE

        val steppable = categories && state.period.isShiftable
        btnPrevPeriod.visibility = if (steppable) View.VISIBLE else View.INVISIBLE
        btnNextPeriod.visibility = btnPrevPeriod.visibility
        btnNextPeriod.isEnabled = state.canGoForward
        btnNextPeriod.alpha = if (state.canGoForward) 1f else DISABLED_ALPHA
        swipeContainer.isSwipeEnabled = steppable
        swipeContainer.canSwipeForward = state.canGoForward
    }

    /**
     * The balance line, plus the two figures beside it.
     *
     * Drawn only while Trends is showing: the series is loaded lazily, so on Categories there is
     * nothing to draw and setting an empty one would clear a chart nobody is looking at.
     */
    private fun renderTrends(trends: TrendsUiState) {
        val series = trends.balanceSeries
        val empty = series.isEmpty()
        btnPickScope.text = "${scopeLabel(trends.scope, trends.accounts)}  \u25BE"

        balanceLine.visibility = if (empty) View.GONE else View.VISIBLE
        cardFlowTrend.visibility = if (empty) View.GONE else View.VISIBLE
        tvTrendsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        tvTrendsEmpty.text = when {
            trends.isLoading -> "Loading"
            !trends.hasAccounts -> "No accounts in this scope"
            else -> "Nothing to chart in this period"
        }

        tvBalanceLatest.text = if (empty) "" else formatRupees(trends.latestBalancePaise)
        if (empty) {
            tvBalanceChange.text = ""
            tvBalanceChange.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        } else {
            renderBalanceChange(trends.changePaise)
        }
        if (empty) return

        val stride = TrendsBuckets.labelStride(series.size)
        val format = bucketFormat(trends.bucket)
        val labels = trends.bucketStarts.mapIndexed { index, start ->
            if (index % stride == 0) format.format(Date(start)) else null
        }
        balanceLine.isPanEnabled = trends.canPan
        flowChart.isPanEnabled = trends.canPan
        balanceLine.setSeries(series, labels)

        // The same labels on both, because the two charts share a bucket layout so that a peak in
        // one can be read directly above the peak in the other.
        flowChart.setSeries(trends.incomeSeries, trends.expenseSeries, labels)
        tvFlowSummary.text = flowSummary(trends)
    }

    /**
     * Both totals and the difference, since the bars and the line answer "which was bigger" only
     * roughly. Phrased as "kept" or "over" rather than signed, which reads at a glance.
     */
    private fun flowSummary(trends: TrendsUiState): String {
        val income = trends.incomeSeries.sum()
        val expense = trends.expenseSeries.sum()
        val net = income - expense
        val verdict = when {
            net > 0L -> "kept ${formatRupees(net)}"
            net < 0L -> "${formatRupees(-net)} over"
            else -> "level"
        }
        return "In ${formatRupees(income)} · Out ${formatRupees(expense)} · $verdict"
    }

    /**
     * Liquid / All accounts / each account / Choose accounts.
     *
     * An AlertDialog list, matching the period menu beside it, with the multi-select chained off the
     * last row. The app has no multi-select anywhere else, and setMultiChoiceItems is the cheapest
     * thing that still looks like the rest of it.
     */
    private fun showScopeMenu() {
        val state = viewModel.uiState.value ?: return
        val accounts = state.trends.accounts
        val labels = listOf("Liquid (cash and savings)", "All accounts") +
            accounts.map { accountLabel(it) } +
            listOf("Choose accounts\u2026")

        AlertDialog.Builder(this)
            .setTitle("Balance for")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> viewModel.selectScope(AccountScope.Liquid)
                    1 -> viewModel.selectScope(AccountScope.Total)
                    labels.lastIndex -> showCustomScopePicker(accounts, state.trends.scope)
                    else -> viewModel.selectScope(AccountScope.Single(accounts[which - 2].id))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomScopePicker(accounts: List<Account>, current: AccountScope) {
        if (accounts.isEmpty()) return
        val alreadyIn = when (current) {
            is AccountScope.Custom -> current.ids
            is AccountScope.Single -> setOf(current.id)
            else -> emptySet()
        }
        val checked = accounts.map { it.id in alreadyIn }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Choose accounts")
            .setMultiChoiceItems(
                accounts.map { accountLabel(it) }.toTypedArray(),
                checked
            ) { _, index, isChecked -> checked[index] = isChecked }
            .setPositiveButton("Done") { _, _ ->
                val picked = accounts.filterIndexed { index, _ -> checked[index] }.map { it.id }.toSet()
                // An empty pick would draw a flat zero line; treat it as "never mind".
                if (picked.isNotEmpty()) viewModel.selectScope(AccountScope.Custom(picked))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun accountLabel(account: Account): String =
        if (account.isArchived) "${account.label} (archived)" else account.label

    /** Named scopes keep their name; a hand-picked set is counted rather than listed. */
    private fun scopeLabel(scope: AccountScope, accounts: List<Account>): String = when (scope) {
        AccountScope.Liquid -> "Liquid"
        AccountScope.Total -> "All accounts"
        is AccountScope.Single ->
            accounts.firstOrNull { it.id == scope.id }?.label ?: "1 account"
        is AccountScope.Custom -> {
            val known = accounts.count { it.id in scope.ids }
            if (known == 1) accounts.first { it.id in scope.ids }.label else "$known accounts"
        }
    }

    /**
     * Movement across the window, not the balance itself. The direction is a real tinted drawable on
     * the label rather than the black-triangle character it used to be, so it follows the theme and
     * carries the same positive/negative colours as every other amount in the app.
     */
    private fun renderBalanceChange(delta: Long) {
        if (delta == 0L) {
            tvBalanceChange.text = "No change this period"
            tvBalanceChange.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            tvBalanceChange.setTextColor(themeColor(ThemeAttr.amountNeutral))
            return
        }
        val up = delta > 0L
        val tint = themeColor(if (up) ThemeAttr.positive else ThemeAttr.negative)
        tvBalanceChange.text = formatRupees(kotlin.math.abs(delta))
        tvBalanceChange.setTextColor(tint)
        tvBalanceChange.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (up) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward, 0, 0, 0
        )
        TextViewCompat.setCompoundDrawableTintList(tvBalanceChange, ColorStateList.valueOf(tint))
    }

    private fun bucketFormat(bucket: TrendBucket): SimpleDateFormat = when (bucket) {
        TrendBucket.HOUR -> SimpleDateFormat("h a", Locale.getDefault())
        TrendBucket.DAY, TrendBucket.WEEK -> shortFmt
        TrendBucket.MONTH -> SimpleDateFormat("MMM", Locale.getDefault())
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
            colors = listOf(categoryColor(drilled))
        } else {
            columns = days.map { it.segments }
            colors = state.breakdown.slices.map { categoryColor(it) }
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

    /**
     * A slice's colour, resolved for the current mode. [CategorySlice] carries only the palette key;
     * see ChartColors for why the two halves are separate.
     */
    private fun categoryColor(slice: CategorySlice): Int =
        ChartColors.forCategory(this, slice.categoryId)

    private fun buildLegend(slices: List<CategorySlice>, totalPaise: Long, drillable: Boolean) {
        legendContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        slices.forEach { slice ->
            val row = inflater.inflate(R.layout.item_stat_legend, legendContainer, false)
            row.findViewById<View>(R.id.viewLegendSwatch).backgroundTintList =
                ColorStateList.valueOf(categoryColor(slice))
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

    /**
     * On Trends the label names the panned window rather than the period, because that is what is
     * actually on screen once a drag has moved it -- and the chevrons are hidden there, so nothing
     * else says where in history the charts are sitting.
     */
    private fun rangeLabel(state: StatisticsUiState): String {
        val trends = state.trends
        if (state.section == StatsSection.TRENDS && trends.balanceSeries.isNotEmpty()) {
            val first = shortFmt.format(Date(trends.windowStart))
            val last = shortFmt.format(Date(trends.windowEndExclusive - 1))
            return if (first == last) dayFmt.format(Date(trends.windowStart)) else "$first - $last"
        }
        return periodRangeLabel(state)
    }

    private fun periodRangeLabel(state: StatisticsUiState): String = when (state.period) {
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
