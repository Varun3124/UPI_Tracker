package com.varun.upitracker.domain.statistics

import com.varun.upitracker.database.model.CategoryTotal
import com.varun.upitracker.database.model.PayeeTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryPaletteTest {

    @Test
    fun sameCategoryAlwaysGetsTheSameSlot() {
        assertEquals(CategoryPalette.indexFor(7L), CategoryPalette.indexFor(7L))
    }

    /**
     * The property the charts rely on: a category's colour depends only on its id, never on which
     * other categories happen to be present. Otherwise a slice would change colour between periods.
     */
    @Test
    fun slotIsIndependentOfTheSurroundingSet() {
        val alone = CategoryPalette.indexFor(3L)
        val crowd = listOf(1L, 2L, 3L, 9L, 12L).map { CategoryPalette.indexFor(it) }
        assertEquals(alone, crowd[2])
    }

    @Test
    fun theFirstSixteenIdsAreAllDistinct() {
        val slots = (1L..16L).map { CategoryPalette.indexFor(it) }
        assertEquals(16, slots.toSet().size)
    }

    /**
     * Every slot has to exist in the resource arrays. Those are Android resources, so what can be
     * checked here is the half that is pure: no id ever escapes the declared range.
     */
    @Test
    fun everySlotIsInRange() {
        val slots = (-50L..50L).map { CategoryPalette.indexFor(it) }
        assertTrue(slots.all { it in 0 until CategoryPalette.SIZE })
    }

    /** Ids start at 1, but a defensive 0 or a negative must not throw. */
    @Test
    fun handlesZeroAndNegativeIds() {
        assertEquals(0, CategoryPalette.indexFor(0L))
        assertEquals(11, CategoryPalette.indexFor(-5L))
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
    fun foldedSlicesCarryTheSamePaletteKeysAsADirectQuery() {
        val folded = StatsAggregator.foldDays(epochs, labels, week).slices.map { it.categoryId }
        val direct = StatsAggregator.toSlices(
            listOf(total(1, "Food", 60000), total(2, "Transport", 43000), total(3, "Gift", 40000))
        ).map { it.categoryId }
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
        assertNotEquals(
            CategoryPalette.indexFor(slices[0].categoryId),
            CategoryPalette.indexFor(slices[1].categoryId)
        )
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
class PayeeSliceTest {

    private fun merchant(id: Long, name: String, paise: Long) = PayeeTotal(id, null, name, paise)
    private fun friend(id: Long, name: String, paise: Long) = PayeeTotal(null, id, name, paise)

    @Test
    fun orderedLargestFirst() {
        val slices = StatsAggregator.toPayeeSlices(
            listOf(merchant(1, "Swiggy", 200), merchant(2, "Zomato", 900))
        )
        assertEquals(listOf("Zomato", "Swiggy"), slices.map { it.name })
    }

    @Test
    fun nonPositivePayeesAreDropped() {
        val slices = StatsAggregator.toPayeeSlices(
            listOf(merchant(1, "Swiggy", 0), merchant(2, "Zomato", 400))
        )
        assertEquals(listOf("Zomato"), slices.map { it.name })
    }

    /**
     * A gift has a friend where a purchase has a merchant. Negating one id space keeps the two
     * from sharing a colour when the numeric ids happen to coincide.
     */
    @Test
    fun aMerchantAndAFriendWithTheSameIdDoNotShareAColour() {
        val slices = StatsAggregator.toPayeeSlices(
            listOf(merchant(7, "Swiggy", 500), friend(7, "Asha", 400))
        )
        assertEquals(2, slices.map { CategoryPalette.indexFor(it.categoryId) }.toSet().size)
        assertEquals(listOf(7L, -7L), slices.map { it.categoryId })
    }

    @Test
    fun colourIsStableAcrossDifferentSurroundingSets() {
        val alone = StatsAggregator.toPayeeSlices(listOf(merchant(3, "Uber", 100))).single().categoryId
        val crowded = StatsAggregator.toPayeeSlices(
            listOf(merchant(1, "A", 900), merchant(3, "Uber", 100), friend(2, "B", 500))
        ).first { it.name == "Uber" }.categoryId
        assertEquals(CategoryPalette.indexFor(alone), CategoryPalette.indexFor(crowded))
    }

    /** A merchant that was never saved has neither id; it still needs a slice. */
    @Test
    fun anUnsavedPayeeStillGetsASlice() {
        val slices = StatsAggregator.toPayeeSlices(
            listOf(PayeeTotal(null, null, "Corner Shop", 300))
        )
        assertEquals(1, slices.size)
        assertEquals(0L, slices.single().categoryId)
    }
}
