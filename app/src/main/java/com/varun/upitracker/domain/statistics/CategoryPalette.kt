package com.varun.upitracker.domain.statistics

/**
 * The palette slot a category is drawn in, everywhere it is drawn.
 *
 * Keyed on the category id alone -- never on the slice's rank, never on how many categories the
 * current window happens to contain. That is the whole point: step from a week where Transport was
 * third to one where it is first and it keeps the same colour, so the pie and the bars beneath it
 * read as one picture rather than two.
 *
 * Rename-safe, because the id outlives a rename and `categories` has no colour column to migrate.
 * Delete-safe, because SQLite's AUTOINCREMENT never reissues an id, so a deleted category cannot
 * hand its colour to a new one.
 *
 * Two categories share a slot only when their ids are congruent modulo the palette size. Ids are
 * handed out sequentially from 1, so that needs a seventeenth category -- and even then it is one
 * pair sharing a hue, not a reshuffle.
 *
 * This object deals only in *which* slot, not what the slot looks like. The actual colours live in
 * `res/values/arrays.xml` and `res/values-night/arrays.xml` so a category can be a deep blue on a
 * white card and a light blue on a navy one; `ui.theme.ChartColors` joins the two halves. Keeping
 * the index maths here means it stays free of Android and stays unit-testable.
 */
object CategoryPalette {

    /**
     * Must match the length of `R.array.chart_category_colors` in both modes. [indexFor] takes the
     * real array length as an argument, so a mismatch shows up as a shifted palette rather than an
     * index out of bounds -- but keep the three in step anyway.
     */
    const val SIZE = 16

    /**
     * `floorMod`, not `%`: a defensive negative id (the payee pie negates friend ids to keep them
     * from colliding with merchant ids) must still land inside the array.
     */
    fun indexFor(categoryId: Long, size: Int = SIZE): Int =
        Math.floorMod(categoryId, size.toLong()).toInt()
}
