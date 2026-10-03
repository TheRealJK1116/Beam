package dev.beam.feature.nfc

/**
 * The APDU dialect two Beam devices speak across the NFC gap.
 *
 * Android Beam (NDEF push) was removed from the platform, so the tap is rebuilt out of the one
 * phone-to-phone NFC mechanism that remains: the receiver runs a Host Card Emulation service
 * and pretends to be a smart card, while the sender puts its radio into reader mode and reads
 * it. That inverts the intuition — the device being *sent to* is the one that answers — but it
 * is what makes receiving work on a phone that is locked with the app closed.
 *
 * The whole exchange is two commands and must finish inside the fraction of a second the phones
 * are actually touching.
 */
object BeamApdu {

    /**
     * Beam's application id. Proprietary (0xF…) range, "Beam" in ASCII, then a version byte, so
     * an incompatible future protocol can simply register a different AID.
     */
    val AID: ByteArray = byteArrayOf(0xF0.toByte(), 0x42, 0x65, 0x61, 0x6D, 0x01)

    val AID_HEX: String = AID.joinToString("") { "%02X".format(it) }

    /** ISO 7816-4 SELECT by name. */
    private val SELECT_HEADER = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)

    /** Proprietary class/instruction asking for the session token. */
    private const val CLA_BEAM = 0x80.toByte()
    private const val INS_GET_SESSION = 0x10.toByte()

    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69, 0x85.toByte())
    val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    val SW_UNKNOWN = byteArrayOf(0x6F, 0x00)

    fun selectCommand(): ByteArray =
        SELECT_HEADER + AID.size.toByte() + AID + 0x00

    fun getSessionCommand(): ByteArray =
        byteArrayOf(CLA_BEAM, INS_GET_SESSION, 0x00, 0x00, 0x00)

    fun isSelectAid(apdu: ByteArray): Boolean {
        if (apdu.size < SELECT_HEADER.size + 1 + AID.size) return false
        if (!apdu.copyOfRange(0, 4).contentEquals(SELECT_HEADER)) return false
        val length = apdu[4].toInt() and 0xFF
        if (length != AID.size) return false
        return apdu.copyOfRange(5, 5 + AID.size).contentEquals(AID)
    }

    fun isGetSession(apdu: ByteArray): Boolean =
        apdu.size >= 2 && apdu[0] == CLA_BEAM && apdu[1] == INS_GET_SESSION

    /** Splits a card response into its body and its two status bytes. */
    fun responseBody(response: ByteArray): ByteArray? {
        if (response.size < 2) return null
        val sw = response.copyOfRange(response.size - 2, response.size)
        if (!sw.contentEquals(SW_OK)) return null
        return response.copyOfRange(0, response.size - 2)
    }

    fun statusOf(response: ByteArray): ByteArray =
        if (response.size < 2) SW_UNKNOWN else response.copyOfRange(response.size - 2, response.size)
}
