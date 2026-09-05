package com.varun.upitracker.domain.statistics

import com.varun.upitracker.database.model.CategoryTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryPaletteTest {

    @Test
    fun sameCategoryAlwaysGetsTheSameColour() {
        assertEquals(CategoryPalette.colorFor(7L), CategoryPalette.colorFor(7L))
    }

    /**
     * The property the charts rely on: a category's colour depends only on its id, never on which
     * other categories happen to be present. Otherwise a slice would change colour between periods.
     */
    @Test
    fun colourIsIndependentOfTheSurroundingSet() {
        val alone = CategoryPalette.colorFor(3L)
        val crowd = listOf(1L, 2L, 3L, 9L, 12L).map(CategoryPalette::colorFor)
        assertEquals(alone, crowd[2])
    }

    @Test
    fun theFirstSixteenIdsAreAllDistinct() {
        val colours = (1L..16L).map(CategoryPalette::colorFor)
        assertEquals(16, colours.toSet().size)
    }

    @Test
    fun neutralIsNeverIssuedAsACategoryColour() {
        assertTrue(CategoryPalette.PALETTE.none { it == CategoryPalette.NEUTRAL })
    }

    /** Ids start at 1, but a defensive 0 or a negative must not throw. */
    @Test
    fun handlesZeroAndNegativeIds() {
        CategoryPalette.colorFor(0L)
        CategoryPalette.colorFor(-5L)
    }
}

class PieGeometryTest {

    @Test
    fun sweepsAlwaysCloseTheCircle() {
        listOf(
            listOf(100L),
            listOf(100L, 200L),
            listOf(1L, 1L, 1L),
            listOf(333L, 333L, 333L),
            List(40) { 7L }
        ).forEach { values ->
            assertEquals(
                "for ${values.size} slices",
                360f,
                PieGeometry.sweeps(values).sum(),
                0.0001f
            )
        }
    }

    @Test
    fun singleSliceIsAFullCircle() {
        assertEquals(listOf(360f), PieGeometry.sweeps(listOf(500L)))
    }

    @Test
    fun allZeroDoesNotDivideByZero() {
        assertEquals(listOf(0f, 0f), PieGeometry.sweeps(listOf(0L, 0L)))
    }

    @Test
    fun sweepsAreProportional() {
        val sweeps = PieGeometry.sweeps(listOf(300L, 100L))
        assertEquals(270f, sweeps[0], 0.01f)
        assertEquals(90f, sweeps[1], 0.01f)
    }
}

class StatsAggregatorTest {

    private fun total(id: Long, name: String, paise: Long) = CategoryTotal(id, name, paise)

    @Test
    fun slicesAreOrderedLargestFirst() {
        val slices = StatsAggregator.toSlices(
            listOf(total(1, "Food", 100), total(2, "Transport", 500), total(3, "Gift", 300))
        )
        assertEquals(listOf("Transport", "Gift", "Food"), slices.map { it.name })
    }

    @Test
    fun equalAmountsBreakTiesByIdSoOrderingIsDeterministic() {
        val slices = StatsAggregator.toSlices(
            listOf(total(9, "Nine", 100), total(2, "Two", 100), total(5, "Five", 100))
        )
        assertEquals(listOf(2L, 5L, 9L), slices.map { it.categoryId })
    }

    @Test
    fun nonPositiveCategoriesAreDropped() {
        val slices = StatsAggregator.toSlices(listOf(total(1, "Food", 0), total(2, "Gift", 400)))
        assertEquals(listOf("Gift"), slices.map { it.name })
    }

    // --- the fold ---------------------------------------------------------

    private val week = listOf(
        listOf(total(1, "Food", 40000)),                               // Mon
        emptyList(),                                                   // Tue
        listOf(total(2, "Transport", 25000)),                          // Wed
        listOf(total(2, "Transport", 18000), total(1, "Food", 5000)),  // Thu
        listOf(total(3, "Gift", 40000)),                               // Fri
        listOf(total(1, "Food", 15000)),                               // Sat
        emptyList()                                                    // Sun
    )
    private val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val epochs = (0L until 7L).map { 1_700_000_000_000L + it * 86_400_000L }

    /** The guarantee the stacked bar exists to keep: the columns sum to the pie. */
    @Test
    fun dayTotalsSumToTheSliceTotals() {
        val result = StatsAggregator.foldDays(epochs, labels, week)
        assertEquals(result.totalPaise, result.days.sumOf { it.totalPaise })
        assertEquals(143000L, result.totalPaise)
    }

    @Test
    fun eachCategorySumsAcrossDaysToItsSlice() {
        val result = StatsAggregator.foldDays(epochs, labels, week)
        result.slices.forEachIndexed { index, slice ->
            assertEquals(slice.name, slice.paise, result.days.sumOf { it.segments[index] })
        }
    }

    /** Every column is expressed against the same ordered category list, so colours line up. */
    @Test
    fun everyDayHasOneSegmentPerSliceInSliceOrder() {
        val result = StatsAggregator.foldDays(epochs, labels, week)
        result.days.forEach { assertEquals(result.slices.size, it.segments.size) }
    }

    @Test
    fun aCategoryAbsentOnADayGetsAZeroInItsSlotRatherThanAMissingSlot() {
        val result = StatsAggregator.foldDays(epochs, labels, week)
        val foodIndex = result.slices.indexOfFirst { it.name == "Food" }
        assertEquals(0L, result.days[1].segments[foodIndex])   // Tuesday had nothing
        assertEquals(40000L, result.days[0].segments[foodIndex])
    }

    @Test
    fun emptyDaysStillOccupyTheirColumn() {
        val result = StatsAggregator.foldDays(epochs, labels, week)
        assertEquals(7, result.days.size)
        assertEquals(0L, result.days[6].totalPaise)
    }

    @Test
    fun foldedSlicesCarryTheSameColoursAsADirectQuery() {
        val folded = StatsAggregator.foldDays(epochs, labels, week).slices.associate { it.categoryId to it.color }
        val direct = StatsAggregator.toSlices(
            listOf(total(1, "Food", 60000), total(2, "Transport", 43000), total(3, "Gift", 40000))
        ).associate { it.categoryId to it.color }
        assertEquals(direct, folded)
    }

    @Test
    fun anEmptyWeekProducesNoSlicesButKeepsItsColumns() {
        val result = StatsAggregator.foldDays(epochs, labels, List(7) { emptyList() })
        assertTrue(result.slices.isEmpty())
        assertEquals(7, result.days.size)
        assertEquals(0L, result.totalPaise)
    }

    @Test
    fun mismatchedLabelCountIsRejected() {
        val failed = runCatching { StatsAggregator.foldDays(epochs, listOf("Mon"), week) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun differentCategoriesGetDifferentColours() {
        val slices = StatsAggregator.foldDays(epochs, labels, week).slices
        assertNotEquals(slices[0].color, slices[1].color)
    }

    /** The bar chart needs each column's day to open it on tap. */
    @Test
    fun eachDayCarriesItsOwnStartEpoch() {
        val result = StatsAggregator.foldDays(epochs, labels, week)
        assertEquals(epochs, result.days.map { it.dayStartEpoch })
    }
}

class BarAxisTest {

    private fun rupees(r: Long) = r * 100L

    @Test
    fun quietWeeksStillGetTheFullAxis() {
        assertEquals(rupees(3000), BarAxis.axisMaxPaise(0L))
        assertEquals(rupees(3000), BarAxis.axisMaxPaise(rupees(400)))
    }

    @Test
    fun exactlyTheFloorDoesNotGrowTheAxis() {
        assertEquals(rupees(3000), BarAxis.axisMaxPaise(rupees(3000)))
    }

    @Test
    fun anUnusualDayGrowsTheAxisToTheNextFiveHundred() {
        assertEquals(rupees(3500), BarAxis.axisMaxPaise(rupees(3200)))
        assertEquals(rupees(3500), BarAxis.axisMaxPaise(rupees(3500)))
        assertEquals(rupees(4000), BarAxis.axisMaxPaise(rupees(3501)))
    }

    /** Two different weeks under the floor must scale identically, or heights lie. */
    @Test
    fun weeksBelowTheFloorShareOneScale() {
        assertEquals(BarAxis.axisMaxPaise(rupees(200)), BarAxis.axisMaxPaise(rupees(2900)))
    }

    @Test
    fun gridlinesAreEveryFiveHundredUpToTheMax() {
        val lines = BarAxis.gridlinesPaise(rupees(3000))
        assertEquals(6, lines.size)
        assertEquals(rupees(500), lines.first())
        assertEquals(rupees(3000), lines.last())
        lines.forEach { assertEquals(0L, it % BarAxis.STEP_PAISE) }
    }

    @Test
    fun labelsThinOutOnceTheAxisGrowsPastSixLines() {
        assertEquals(1, BarAxis.labelStride(BarAxis.gridlinesPaise(rupees(3000)).size))
        assertEquals(2, BarAxis.labelStride(BarAxis.gridlinesPaise(rupees(5000)).size))
    }
}
