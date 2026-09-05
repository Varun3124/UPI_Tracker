package com.varun.upitracker.domain.statistics

/**
 * The colour a category is drawn in, everywhere it is drawn.
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
 * Two categories share a colour only when their ids are congruent modulo the palette size. Ids are
 * handed out sequentially from 1, so that needs a seventeenth category -- and even then it is one
 * pair sharing a hue, not a reshuffle.
 */
object CategoryPalette {

    /**
     * Material 600s, ordered so consecutively issued ids get well-separated hues, and all dark
     * enough to read as fills on the app's `#F5F5F5` page and `#FFFFFF` cards.
     */
    val PALETTE = intArrayOf(
        0xFF1E88E5.toInt(), // blue
        0xFFE53935.toInt(), // red
        0xFF43A047.toInt(), // green
        0xFFFB8C00.toInt(), // orange
        0xFF8E24AA.toInt(), // purple
        0xFF00ACC1.toInt(), // cyan
        0xFFD81B60.toInt(), // pink
        0xFF7CB342.toInt(), // light green
        0xFF3949AB.toInt(), // indigo
        0xFFF4511E.toInt(), // deep orange
        0xFF00897B.toInt(), // teal
        0xFF5E35B1.toInt(), // deep purple
        0xFFC0CA33.toInt(), // lime
        0xFF039BE5.toInt(), // light blue
        0xFF6D4C41.toInt(), // brown
        0xFF546E7A.toInt()  // blue grey
    )

    /** Never issued from [PALETTE]: the empty-state ring, and the bar chart's empty-day stub. */
    const val NEUTRAL = 0xFFBDBDBD.toInt()

    fun colorFor(categoryId: Long): Int =
        PALETTE[Math.floorMod(categoryId, PALETTE.size.toLong()).toInt()]
}
