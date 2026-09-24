package com.varun.upitracker.domain.mailbox

/**
 * What to do with this install's mailbox keys. See [IdentityPolicy].
 *
 * [replacesPublished] on any decision means friends currently encrypt to a key whose private half
 * nobody holds any more. Their links still point at it, so they have to re-link, and the screen has
 * to say so.
 */
sealed interface IdentityDecision {

    /** Keep this phone's key; copy it to Drive and publish it where either is missing or stale. */
    data class UseLocal(
        val uploadToDrive: Boolean,
        val publish: Boolean,
        val replacesPublished: Boolean
    ) : IdentityDecision

    /** Take the Drive copy: a reinstall, or another device holds the key friends encrypt to. */
    data class RestoreFromDrive(val publish: Boolean, val replacesPublished: Boolean) : IdentityDecision

    /** No private key exists anywhere. Make one, put it in Drive, and only then publish it. */
    data class Generate(val replacesPublished: Boolean) : IdentityDecision
}

/**
 * Where the mailbox keys come from, decided from fingerprints alone. Each input is null where no key
 * exists:
 *  - `local` -- the private key on this phone;
 *  - `drive` -- the private key in Drive's `appDataFolder`, the copy a reinstall recovers;
 *  - `published` -- the public key in `/users/{uid}`, the one friends encrypt to.
 *
 * Written down and tested like [com.varun.upitracker.domain.backup.BackupPolicy], for a similar
 * reason: getting it wrong silently makes undelivered messages unreadable.
 *
 * The one rule every caller has to honour: **a key is uploaded to Drive before it is published.**
 * Publishing first opens a window where friends encrypt to a key that one crash would lose.
 */
object IdentityPolicy {

    fun decide(local: String?, drive: String?, published: String?): IdentityDecision {
        if (local != null) {
            return when (drive) {
                null, local -> IdentityDecision.UseLocal(
                    uploadToDrive = drive == null,
                    publish = published != local,
                    replacesPublished = published != null && published != local
                )
                // Phone and Drive disagree: another install changed the key. Whichever one friends
                // encrypt to is current; when neither is, Drive wins so every install converges.
                else -> when (published) {
                    drive -> IdentityDecision.RestoreFromDrive(publish = false, replacesPublished = false)
                    local -> IdentityDecision.UseLocal(uploadToDrive = true, publish = false, replacesPublished = false)
                    else -> IdentityDecision.RestoreFromDrive(publish = true, replacesPublished = published != null)
                }
            }
        }
        if (drive != null) {
            return IdentityDecision.RestoreFromDrive(
                publish = published != drive,
                replacesPublished = published != null && published != drive
            )
        }
        return IdentityDecision.Generate(replacesPublished = published != null)
    }
}
