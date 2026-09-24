package com.varun.upitracker.data.mailbox

import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Seals the mailbox's small secrets -- the Firebase refresh token and the private keys -- under an
 * AES-GCM key that never leaves this phone's Android Keystore.
 *
 * The files themselves live in `noBackupFilesDir`, which Android's Auto Backup and device-to-device
 * transfer both skip. That is the real protection against the secrets ending up somewhere else; the
 * Keystore wrapping is so that a copy of the app's files is still useless on its own.
 *
 * Anything that will not open -- the Keystore key is gone after a factory reset, say -- reads as
 * absent. The session is then signed out, and the keys come back from Drive.
 */
internal object LocalVault {

    private const val KEY_ALIAS = "dhanmoney_mailbox_vault"

    @Synchronized
    private fun aead(): Aead {
        if (!AndroidKeystore.hasKey(KEY_ALIAS)) AndroidKeystore.generateNewAes256GcmKey(KEY_ALIAS)
        return AndroidKeystore.getAead(KEY_ALIAS)
    }

    /** [label] binds the ciphertext to what it is, so the session file cannot be swapped for a key file. */
    fun write(file: File, plaintext: ByteArray, label: String) {
        val sealed = aead().encrypt(plaintext, label.toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        // Written aside and renamed over, so a crash mid-write never leaves half a key behind.
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(sealed)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Could not store ${file.name}.")
        }
    }

    fun read(file: File, label: String): ByteArray? {
        if (!file.exists()) return null
        return try {
            aead().decrypt(file.readBytes(), label.toByteArray(Charsets.UTF_8))
        } catch (error: GeneralSecurityException) {
            null
        } catch (error: IOException) {
            null
        }
    }
}
