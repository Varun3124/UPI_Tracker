package com.varun.upitracker.util

/**
 * The initials shown in an avatar circle: the first letter of up to the first two words.
 *
 * `Jayneel Shah` -> `JS`, `Zeel` -> `Z`.
 *
 * Lives in `util` rather than beside the avatar drawing code for the same reason [AmountFormat]
 * does: this rule has two callers outside the UI. `SettingsRepository` runs it when it creates a
 * friend, because the result is *stored* in `Friend.avatarInitials`, and the transaction entry
 * screen runs it when it creates one on the fly. A data-layer class reaching into `ui.theme` to
 * share the rule would have the dependency backwards.
 *
 * There used to be three copies of this, and they did not agree: the dashboard's inlined version
 * had no fallback at all, so a friend whose name was somehow blank rendered an empty circle.
 *
 * @param fallback what to show when [name] yields no letters -- `"F"` where the result is persisted
 *   against a friend, so existing rows keep the shape they already have.
 */
fun initialsOf(name: String?, fallback: String = "?"): String {
    val initials = name.orEmpty()
        .split(' ', '\t', '\n')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
    return initials.ifBlank { fallback }
}
