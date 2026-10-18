package com.varun.upitracker.ui.chapter

import android.app.AlertDialog
import android.content.Context
import com.varun.upitracker.data.repository.OutsiderCandidate

/**
 * Asks, one person at a time, whether everyone a batch of transactions names that [chapterName] does
 * not hold should be added to it -- what tagging always did (R5) -- or kept outside, where what they
 * and the user owe each other stays out of the chapter. Asked every time: who deals with whom
 * changes, so nothing is remembered per person.
 *
 * [onDone] gets who to keep outside, or null when the user backed out of tagging altogether.
 */
object OutsiderPrompt {

    fun ask(
        context: Context,
        chapterName: String,
        candidates: List<OutsiderCandidate>,
        onDone: (keepOutside: Set<Long>?) -> Unit
    ) {
        val keepOutside = linkedSetOf<Long>()

        fun askFrom(index: Int) {
            val candidate = candidates.getOrNull(index) ?: return onDone(keepOutside)
            var answered = false
            fun answer(outside: Boolean?) {
                if (answered) return
                answered = true
                when (outside) {
                    null -> onDone(null)
                    true -> {
                        keepOutside += candidate.friendId
                        askFrom(index + 1)
                    }
                    false -> askFrom(index + 1)
                }
            }
            val name = candidate.name
            val message = when {
                !candidate.canKeepOutside ->
                    "Someone in the chapter pays $name, or is paid by them, straight, so they cannot be " +
                        "kept outside it. Add them, or leave these out of the chapter."
                candidate.reroutes ->
                    "Add them to it, or keep them outside: what you and $name owe each other on these " +
                        "stays between the two of you, out of the chapter.\n\nWhere $name and someone in " +
                        "it owe each other, that will go through you."
                else ->
                    "Add them to it, or keep them outside: what you and $name owe each other on these " +
                        "stays between the two of you, out of the chapter."
            }
            val builder = AlertDialog.Builder(context)
                .setTitle("$name isn't in $chapterName")
                .setMessage(message)
                .setPositiveButton("Add to chapter") { _, _ -> answer(false) }
                .setNeutralButton("Cancel") { _, _ -> answer(null) }
                .setOnCancelListener { answer(null) }
            if (candidate.canKeepOutside) builder.setNegativeButton("Keep outside") { _, _ -> answer(true) }
            builder.show()
        }

        askFrom(0)
    }
}
