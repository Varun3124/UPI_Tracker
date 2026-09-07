package com.varun.upitracker.ui.theme

import android.content.res.ColorStateList
import android.widget.TextView
import com.varun.upitracker.ui.ActorType
import com.varun.upitracker.util.initialsOf

/**
 * The initials circle, in one place.
 *
 * It used to exist twice with different rules -- the entry screen looked the colour up from the
 * actor type, the dashboard hardcoded indigo for everyone -- and the "first letter of up to two
 * words" rule existed three times, one of which had no blank fallback. Colour is per *type*, never
 * per person: two friends are meant to look alike, and that is what tells a friend row apart from a
 * merchant row at a glance.
 */
object Avatars {

    /** Background and foreground theme attributes for an [ActorType]. */
    private fun attrsFor(actorType: String): Pair<Int, Int> = when (actorType) {
        ActorType.ME -> ThemeAttr.avatarMe to ThemeAttr.onAvatarMe
        ActorType.MERCHANT -> ThemeAttr.avatarMerchant to ThemeAttr.onAvatarMerchant
        else -> ThemeAttr.avatarFriend to ThemeAttr.onAvatarFriend
    }

    /**
     * Paints [view] as an avatar for [actorType]. The view is expected to carry
     * `@style/Widget.UPI.Avatar`, which supplies the circle drawable; this only tints it.
     */
    fun paint(view: TextView, actorType: String) {
        val (backgroundAttr, foregroundAttr) = attrsFor(actorType)
        view.backgroundTintList = ColorStateList.valueOf(view.themeColor(backgroundAttr))
        view.setTextColor(view.themeColor(foregroundAttr))
    }

    /** Paints the circle and fills in the letters. */
    fun bind(view: TextView, name: String?, actorType: String, fallback: String = "?") {
        paint(view, actorType)
        view.text = initialsOf(name, fallback)
    }
}
