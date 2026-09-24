package com.varun.upitracker.domain.mailbox

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.PredefinedSignatureParameters
import com.google.crypto.tink.signature.SignatureConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Registers Tink's key managers once per process. Every entry point below calls [ensure] first, so
 * no caller has to remember to.
 */
private object TinkSetup {
    init {
        HybridConfig.register()
        SignatureConfig.register()
    }

    fun ensure() = Unit
}

/**
 * Someone's public mailbox keys, as the bytes they published.
 *
 * Kept as bytes rather than parsed handles because the bytes are what gets pinned and fingerprinted:
 * re-serialising a parsed key is not guaranteed to give the same bytes back.
 */
class PublicMailboxKeys private constructor(
    private val encryptionKeyset: ByteArray,
    private val signingKeyset: ByteArray
) {
    val fingerprint: String = KeyFingerprint.of(encryptionKeyset, signingKeyset)

    /** base64url, as stored in `/users/{uid}` and in a link. */
    val encryptionKeyText: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(encryptionKeyset)

    val signingKeyText: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(signingKeyset)

    internal fun hybridEncrypt(): HybridEncrypt =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(encryptionKeyset)
            .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)

    internal fun verifier(): PublicKeyVerify =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(signingKeyset)
            .getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)

    companion object {
        /**
         * Null for anything that is not a usable pair of public keysets -- including one that
         * carries a private key, which `parseKeysetWithoutSecret` refuses outright. Parsed here, so a
         * bad key is refused where it arrives rather than at the first message.
         */
        fun fromText(encryptionKeyText: String, signingKeyText: String): PublicMailboxKeys? = try {
            TinkSetup.ensure()
            PublicMailboxKeys(
                Base64.getUrlDecoder().decode(encryptionKeyText),
                Base64.getUrlDecoder().decode(signingKeyText)
            ).also { keys ->
                keys.hybridEncrypt()
                keys.verifier()
            }
        } catch (error: GeneralSecurityException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        }

        internal fun of(encryptionKeyset: ByteArray, signingKeyset: ByteArray) =
            PublicMailboxKeys(encryptionKeyset, signingKeyset)
    }
}

/**
 * This person's own mailbox keys: HPKE (X25519, HKDF-SHA256, AES-256-GCM) to open what is sealed to
 * them, and Ed25519 to sign what they send.
 *
 * Two keysets rather than one key doing both jobs, because Tink will not let one key do both, and
 * rightly: a key that decrypts should never also be one that signs.
 */
class MailboxKeys private constructor(
    private val encryption: KeysetHandle,
    private val signing: KeysetHandle
) {
    val publicKeys: PublicMailboxKeys = PublicMailboxKeys.of(
        TinkProtoKeysetFormat.serializeKeysetWithoutSecret(encryption.publicKeysetHandle),
        TinkProtoKeysetFormat.serializeKeysetWithoutSecret(signing.publicKeysetHandle)
    )

    val fingerprint: String get() = publicKeys.fingerprint

    internal fun hybridDecrypt(): HybridDecrypt =
        encryption.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)

    internal fun signer(): PublicKeySign =
        signing.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java)

    /**
     * Both private keysets, **unencrypted**. Only for the Drive copy, which the user's Google account
     * protects, and for wrapping under the Android Keystore on this phone. Never logged, and never
     * sent anywhere else.
     */
    fun serializePrivate(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF(MAGIC)
            writeBlock(data, TinkProtoKeysetFormat.serializeKeyset(encryption, InsecureSecretKeyAccess.get()))
            writeBlock(data, TinkProtoKeysetFormat.serializeKeyset(signing, InsecureSecretKeyAccess.get()))
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAGIC = "DHMK1"

        /** A keyset is a few hundred bytes; this only bounds a corrupt file. */
        private const val MAX_KEYSET_BYTES = 16 * 1024

        fun generate(): MailboxKeys {
            TinkSetup.ensure()
            val hpke = HpkeParameters.builder()
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .setVariant(HpkeParameters.Variant.TINK)
                .build()
            return MailboxKeys(
                KeysetHandle.generateNew(hpke),
                KeysetHandle.generateNew(PredefinedSignatureParameters.ED25519)
            )
        }

        /** Null for anything that is not exactly what [serializePrivate] writes. */
        fun parsePrivate(bytes: ByteArray): MailboxKeys? = try {
            TinkSetup.ensure()
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readUTF() != MAGIC) return null
                val encryption = readBlock(data) ?: return null
                val signing = readBlock(data) ?: return null
                if (data.read() != -1) return null
                MailboxKeys(
                    TinkProtoKeysetFormat.parseKeyset(encryption, InsecureSecretKeyAccess.get()),
                    TinkProtoKeysetFormat.parseKeyset(signing, InsecureSecretKeyAccess.get())
                ).also { keys ->
                    // A keyset of the wrong kind parses happily and fails only at first use.
                    keys.hybridDecrypt()
                    keys.signer()
                }
            }
        } catch (error: IOException) {
            null
        } catch (error: GeneralSecurityException) {
            null
        }

        private fun writeBlock(data: DataOutputStream, block: ByteArray) {
            data.writeInt(block.size)
            data.write(block)
        }

        private fun readBlock(data: DataInputStream): ByteArray? {
            val size = data.readInt()
            if (size <= 0 || size > MAX_KEYSET_BYTES) return null
            return ByteArray(size).also(data::readFully)
        }
    }
}

/**
 * A message out of its seal, but not yet trusted.
 *
 * The envelope is private on purpose: the only way to read it as trusted is [verifiedBy], so no
 * caller can import a parcel it forgot to check the signature of.
 */
class Unsealed internal constructor(
    private val envelope: MailboxEnvelope,
    private val fields: ByteArray,
    private val signature: ByteArray
) {
    val kind: MailboxKind get() = envelope.kind

    /** The envelope, if and only if [keys] signed it. */
    fun verifiedBy(keys: PublicMailboxKeys): MailboxEnvelope? = try {
        keys.verifier().verify(signature, fields)
        envelope
    } catch (error: GeneralSecurityException) {
        null
    }

    /**
     * The body before anything vouches for it. Only for a [MailboxKind.LINK_ACCEPT], which carries
     * the keys it claims to be signed with -- read them, check them against what the sender
     * published, then call [verifiedBy] with them.
     */
    fun unverifiedBody(): String = envelope.body
}

sealed interface UnsealResult {
    class Ok(val unsealed: Unsealed) : UnsealResult

    /**
     * It would not decrypt: sealed to another key, bound to another sender, reader or id, or altered
     * on the way. All of these look the same from here, by design of the cipher.
     */
    data object WontOpen : UnsealResult

    /** It decrypted, but what is inside is not an envelope, or names a different sender, reader or id. */
    data object Malformed : UnsealResult
}

/**
 * Sign-then-seal, and its reverse.
 *
 * The sender signs the envelope's fields with their Ed25519 key, and the fields and signature are
 * sealed together to the reader with HPKE, bound to the sender's uid, the reader's uid and the
 * message id. The server stores bytes it cannot read; a copy moved to another inbox or stored under
 * another id will not open; and a server that swapped a friend's published keys is caught when the
 * signature is checked against the key pinned when the two linked.
 */
object MailboxCrypto {

    fun seal(envelope: MailboxEnvelope, sender: MailboxKeys, recipient: PublicMailboxKeys): ByteArray {
        TinkSetup.ensure()
        val fields = MailboxEnvelopeFormat.fields(envelope)
        val plaintext = MailboxEnvelopeFormat.pack(fields, sender.signer().sign(fields))
        val context = MailboxEnvelopeFormat.contextInfo(envelope.senderUid, envelope.recipientUid, envelope.messageId)
        return recipient.hybridEncrypt().encrypt(plaintext, context)
    }

    /**
     * [fromUid] and [messageId] come from the Firestore document -- its rules-checked `from` and its
     * id -- and [readerUid] is this install's own. None of them is taken from inside the message.
     */
    fun unseal(
        ciphertext: ByteArray,
        reader: MailboxKeys,
        fromUid: String,
        readerUid: String,
        messageId: String
    ): UnsealResult {
        TinkSetup.ensure()
        val context = MailboxEnvelopeFormat.contextInfo(fromUid, readerUid, messageId)
        val plaintext = try {
            reader.hybridDecrypt().decrypt(ciphertext, context)
        } catch (error: GeneralSecurityException) {
            return UnsealResult.WontOpen
        }
        val (fields, signature) = MailboxEnvelopeFormat.unpack(plaintext) ?: return UnsealResult.Malformed
        val envelope = MailboxEnvelopeFormat.parseFields(fields) ?: return UnsealResult.Malformed
        if (envelope.senderUid != fromUid || envelope.recipientUid != readerUid || envelope.messageId != messageId) {
            return UnsealResult.Malformed
        }
        return UnsealResult.Ok(Unsealed(envelope, fields, signature))
    }
}
