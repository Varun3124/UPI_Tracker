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
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.varun.upitracker.R
import com.varun.upitracker.domain.statistics.CategorySlice
import com.varun.upitracker.domain.statistics.ListWindow
import com.varun.upitracker.domain.statistics.PerDayRate
import com.varun.upitracker.domain.statistics.StatisticsPeriods
import com.varun.upitracker.domain.statistics.StatsAggregator
import com.varun.upitracker.domain.statistics.StatsPeriod
import com.varun.upitracker.domain.statistics.serialise
import com.varun.upitracker.ui.AccountScopePicker
import com.varun.upitracker.ui.AllTransactionsActivity
import com.varun.upitracker.ui.attachVerticalStepGesture
import com.varun.upitracker.ui.formatRupees
import com.varun.upitracker.ui.scopeLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.varun.upitracker.ui.theme.ChartColors
import android.widget.ImageButton
import com.varun.upitracker.ui.theme.padRootForSystemBars
import android.content.res.ColorStateList
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
    private lateinit var tvPiePerDay: TextView
    private lateinit var tvStatsEmpty: TextView
    private lateinit var tvCategoryCardTitle: TextView
    private lateinit var btnClearDrill: TextView
    private lateinit var legendContainer: LinearLayout
    private lateinit var cardWeekBars: View
    private lateinit var tvPeakDay: TextView
    private lateinit var weekBars: StackedBarChartView
    private lateinit var swipeContainer: SwipeableFrameLayout
    private lateinit var trendsPanel: View
    private lateinit var periodRow: View
    private lateinit var btnSectionCategories: TextView
    private lateinit var btnSectionTrends: TextView
    private lateinit var balanceGraph: BalanceGraphView
    private lateinit var trendsOverlay: View
    private lateinit var tvBalanceLatest: TextView
    private lateinit var tvSpeculationNote: TextView
    private lateinit var tvTrendsEmpty: TextView
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

        // Back steps through what changed here first -- the week a day was tapped from, a category
        // drilled into -- and only leaves once there is nothing left to undo. See BackHistory.
        onBackPressedDispatcher.addCallback(this) { if (!viewModel.goBack()) finish() }

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
        tvPiePerDay = findViewById(R.id.tvPiePerDay)
        tvStatsEmpty = findViewById(R.id.tvStatsEmpty)
        tvCategoryCardTitle = findViewById(R.id.tvCategoryCardTitle)
        btnClearDrill = findViewById(R.id.btnClearDrill)
        legendContainer = findViewById(R.id.legendContainer)
        cardWeekBars = findViewById(R.id.cardWeekBars)
        tvPeakDay = findViewById(R.id.tvPeakDay)
        weekBars = findViewById(R.id.weekBars)
        swipeContainer = findViewById(R.id.swipeContainer)
        trendsPanel = findViewById(R.id.trendsPanel)
        periodRow = findViewById(R.id.periodRow)
        btnSectionCategories = findViewById(R.id.btnSectionCategories)
        btnSectionTrends = findViewById(R.id.btnSectionTrends)
        balanceGraph = findViewById(R.id.balanceGraph)
        trendsOverlay = findViewById(R.id.trendsOverlay)
        tvBalanceLatest = findViewById(R.id.tvBalanceLatest)
        tvSpeculationNote = findViewById(R.id.tvSpeculationNote)
        tvTrendsEmpty = findViewById(R.id.tvTrendsEmpty)
        btnPickScope = findViewById(R.id.btnPickScope)
        btnPickScope.setOnClickListener { showScopeMenu() }
        // The graph moves itself: nothing here, or in the back history, follows it around.
        findViewById<View>(R.id.btnGraphHome).setOnClickListener { balanceGraph.goHome() }
        findViewById<View>(R.id.btnGraphZoomIn).setOnClickListener { balanceGraph.zoomBy(ZOOM_STEP) }
        findViewById<View>(R.id.btnGraphZoomOut).setOnClickListener { balanceGraph.zoomBy(1 / ZOOM_STEP) }
        btnSectionCategories.setOnClickListener { viewModel.selectSection(StatsSection.CATEGORIES) }
        btnSectionTrends.setOnClickListener { viewModel.selectSection(StatsSection.TRENDS) }
        btnClearDrill.setOnClickListener { viewModel.clearDrill() }
        pieChart.onSliceTapped = { index ->
            // A category drills into its merchants; a merchant, one level down, opens its
            // transactions -- the same as tapping its row in the legend.
            val state = viewModel.uiState.value
            if (state != null) {
                if (state.drilledCategory == null) {
                    state.breakdown.slices.getOrNull(index)?.let { viewModel.drillInto(it) }
                } else {
                    state.payeeSlices.getOrNull(index)?.let { openPayeeTransactions(it) }
                }
            }
        }
        weekBars.onDayTapped = { index ->
            viewModel.uiState.value?.breakdown?.days?.getOrNull(index)
                ?.let { viewModel.selectDay(it.dayStartEpoch) }
        }
        swipeContainer.onSwipe = { direction -> viewModel.step(direction) }

        btnPickPeriod.setOnClickListener { showPeriodMenu() }
        // Dragged up or down it steps the period, as the month button on All Transactions does.
        attachVerticalStepGesture(btnPickPeriod) { delta -> viewModel.step(delta) }
        // The on-screen back arrow leaves the page outright; only the phone's back steps through
        // what changed here (see BackHistory). Leaving clears this page's steps with it.
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
        renderPerDay(tvPiePerDay, total, state.rangeDays)
        tvStatsEmpty.visibility = if (slices.isEmpty()) View.VISIBLE else View.GONE
        tvStatsEmpty.text = if (drilled != null) {
            "Nothing under ${drilled.name} in this period"
        } else {
            "No spending in this period"
        }
        buildLegend(slices, total, state.rangeDays, drillable = drilled == null)

        renderWeekBars(state, drilled)
    }

    /**
     * The two sections share the header and the section tabs. The period control and its row are
     * Categories' alone: Trends is one graph over all of history, zoomed to whatever span is wanted,
     * so a period would only be a second way to say what the fingers already say.
     */
    private fun renderSection(state: StatisticsUiState) {
        val categories = state.section == StatsSection.CATEGORIES
        btnSectionCategories.isSelected = categories
        btnSectionTrends.isSelected = !categories

        swipeContainer.visibility = if (categories) View.VISIBLE else View.GONE
        trendsPanel.visibility = if (categories) View.GONE else View.VISIBLE
        periodRow.visibility = if (categories) View.VISIBLE else View.GONE
        btnPickPeriod.visibility = if (categories) View.VISIBLE else View.GONE

        // Invisible rather than gone on Categories, so a period that cannot step (all time, a custom
        // range) keeps its label centred where the stepping ones put it.
        val steppable = categories && state.period.isShiftable
        btnPrevPeriod.visibility = when {
            !categories -> View.GONE
            steppable -> View.VISIBLE
            else -> View.INVISIBLE
        }
        btnNextPeriod.visibility = btnPrevPeriod.visibility
        btnNextPeriod.isEnabled = state.canGoForward
        btnNextPeriod.alpha = if (state.canGoForward) 1f else DISABLED_ALPHA
        swipeContainer.isSwipeEnabled = steppable
        swipeContainer.canSwipeForward = state.canGoForward
    }

    /**
     * The graph, and the scope and balance floating on it.
     *
     * Drawn only while Trends is showing: the points are loaded lazily, so on Categories there is
     * nothing to draw. The graph is handed the points with the scope as their key, so a new scope
     * opens on the last month again while the same one keeps wherever the graph was.
     */
    private fun renderTrends(trends: TrendsUiState) {
        val points = trends.points
        btnPickScope.text = "${scopeLabel(trends.scope, trends.accounts)}  \u25BE"

        tvTrendsEmpty.visibility = if (points == null) View.VISIBLE else View.GONE
        tvTrendsEmpty.text = when {
            trends.isLoading -> "Loading"
            !trends.hasAccounts -> "No accounts in this scope"
            else -> "Nothing to chart yet"
        }
        if (trends.isLoading && points == null) return

        balanceGraph.setPoints(points, trends.balanceCertainFromEpoch, trends.loadedAtEpoch, trends.scope.serialise())

        tvBalanceLatest.visibility = if (points == null) View.GONE else View.VISIBLE
        tvBalanceLatest.text = formatRupees(trends.latestBalancePaise)
        // The headline figure follows the line: brown while it is still a reconstruction.
        tvBalanceLatest.setTextColor(
            tvBalanceLatest.themeColor(
                if (trends.isLatestSpeculative) ThemeAttr.speculative else ThemeAttr.onSurface
            )
        )
        renderSpeculationNote(trends)
    }

    /**
     * Says out loud what the brown means, since a colour on its own does not.
     *
     * Only while some of the line is a reconstruction. The wording separates the two cases, because
     * "no snapshot yet" is fixable by adding one and "before your first snapshot" is not.
     */
    private fun renderSpeculationNote(trends: TrendsUiState) {
        val points = trends.points
        val certain = trends.balanceCertainFromEpoch
        // Nothing brown on the graph when there is no line, or every point is after the first snapshot.
        if (points == null || (certain != null && points.epochs.first() >= certain)) {
            tvSpeculationNote.visibility = View.GONE
            return
        }
        tvSpeculationNote.visibility = View.VISIBLE
        tvSpeculationNote.text = if (trends.balanceCertainFromEpoch == null) {
            "Brown: worked out from transactions alone. Add a balance snapshot on the Accounts " +
                "screen to anchor it."
        } else {
            "Brown, up to the dashed line: worked out from transactions alone, before the first " +
                "balance snapshot."
        }
    }

    /** See [AccountScopePicker]; the transactions list offers the same choice. */
    private fun showScopeMenu() {
        val state = viewModel.uiState.value ?: return
        AccountScopePicker(this, title = "Balance for")
            .show(state.trends.accounts, state.trends.scope, viewModel::selectScope)
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

    /** The rate beside a figure, hidden when the period is too short for one to mean anything. */
    private fun renderPerDay(view: TextView, paise: Long, rangeDays: Int) {
        val perDay = PerDayRate.perDayPaise(paise, rangeDays)
        view.visibility = if (perDay == null) View.GONE else View.VISIBLE
        view.text = perDay?.let { "${formatRupees(it)}/day" }.orEmpty()
    }

    /**
     * A slice's colour, resolved for the current mode. [CategorySlice] carries only the palette key;
     * see ChartColors for why the two halves are separate.
     */
    private fun categoryColor(slice: CategorySlice): Int =
        ChartColors.forCategory(this, slice.categoryId)

    /**
     * The category split, and -- while drilled -- the merchant split, which comes through here too.
     * One code path, so the two cannot end up describing themselves differently.
     */
    private fun buildLegend(
        slices: List<CategorySlice>,
        totalPaise: Long,
        rangeDays: Int,
        drillable: Boolean
    ) {
        legendContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        slices.forEach { slice ->
            val row = inflater.inflate(R.layout.item_stat_legend, legendContainer, false)
            row.findViewById<View>(R.id.viewLegendSwatch).backgroundTintList =
                ColorStateList.valueOf(categoryColor(slice))
            row.findViewById<TextView>(R.id.tvLegendName).text = slice.name
            row.findViewById<TextView>(R.id.tvLegendAmount).text = formatRupees(slice.paise)
            renderPerDay(row.findViewById(R.id.tvLegendPerDay), slice.paise, rangeDays)
            row.findViewById<TextView>(R.id.tvLegendPercent).text =
                if (totalPaise > 0L) "${(slice.paise * 100.0 / totalPaise).toInt()}%" else ""
            row.isClickable = true
            row.setOnClickListener {
                if (drillable) viewModel.drillInto(slice) else openPayeeTransactions(slice)
            }
            legendContainer.addView(row)
        }
    }

    /**
     * The transactions behind one merchant's slice, over the same span the pie is showing.
     *
     * The list narrows to the merchant alone, not to the category as well: it has no category
     * filter, and what the user wants from here is usually "what did I spend there".
     */
    private fun openPayeeTransactions(slice: CategorySlice) {
        val state = viewModel.uiState.value ?: return
        startActivity(
            AllTransactionsActivity.forPayee(
                context = this,
                payee = StatsAggregator.payeeOf(slice),
                name = slice.name,
                window = ListWindow.of(state.range)
            )
        )
    }

    private fun rangeLabel(state: StatisticsUiState): String = periodRangeLabel(state)

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

        /** How far one press of + or − zooms the graph, both ways at once. */
        const val ZOOM_STEP = 1.6

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
