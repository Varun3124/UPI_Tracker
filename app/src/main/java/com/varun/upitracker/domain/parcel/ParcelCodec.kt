package com.varun.upitracker.domain.parcel

/**
 * What travels between two phones by paste: `UPIX1.<crc32>.<payload>`, framed by [PasteFraming].
 *
 * Only ever a pasted parcel: version 3, or version 1 from an app that predates it. The `1` in the
 * prefix numbers the framing, not the parcel inside, which is what lets an older app get far enough
 * into a newer parcel to say it needs updating. Version 2 names accounts, and a pasted parcel has
 * nothing that verified who wrote it, so version 2 travels by mailbox and is refused here in both
 * directions.
 *
 * The payload is [ParcelFormat] text compressed by [PasteCompression], which says why the framing's
 * checksum runs before anything is inflated.
 */
object ParcelCodec {

    const val PREFIX = "UPIX1"

    private const val FAMILY = "UPIX"

    /**
     * A parcel is a chat message; nothing legitimate comes close to this. The cap exists so a
     * hostile or corrupt payload cannot be inflated into an allocation big enough to kill the app.
     */
    private const val MAX_INFLATED_BYTES = 256 * 1024

    fun encode(parcel: Parcel): String {
        require(parcel.version == ParcelFormat.VERSION) {
            "Only a version ${ParcelFormat.VERSION} parcel can be pasted, not version ${parcel.version}."
        }
        val plaintext = ParcelFormat.format(parcel).toByteArray(Charsets.UTF_8)
        return PasteFraming.frame(PREFIX, PasteCompression.deflate(plaintext))
    }

    fun decode(text: String): ParcelDecodeResult {
        val compressed = when (val framed = PasteFraming.unframe(text, PREFIX, FAMILY)) {
            is PasteFraming.Result.Ok -> framed.payload
            PasteFraming.Result.Empty ->
                return ParcelDecodeResult.Failed("There is nothing here to import.")
            PasteFraming.Result.Foreign ->
                return ParcelDecodeResult.Failed("That does not look like a shared parcel.")
            PasteFraming.Result.OtherVersion ->
                return ParcelDecodeResult.Failed(
                    "This parcel was made by a newer version of the app. Update, then try again."
                )
            PasteFraming.Result.BadChecksum ->
                return ParcelDecodeResult.Failed("This parcel is damaged. Ask for it to be sent again.")
            PasteFraming.Result.Corrupted ->
                return ParcelDecodeResult.Failed(
                    "This parcel is damaged, most likely cut short when it was copied. " +
                        "Ask for it to be sent again."
                )
        }

        val plaintext = PasteCompression.inflate(compressed, MAX_INFLATED_BYTES)?.toString(Charsets.UTF_8)
            ?: return ParcelDecodeResult.Failed("This parcel is damaged. Ask for it to be sent again.")
        return ParcelFormat.parse(plaintext, ParcelFormat.VERSION)
    }
}
