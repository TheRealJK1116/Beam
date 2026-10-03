package dev.beam.transport.nearby

/**
 * The small control protocol that runs inside a Nearby connection.
 *
 * Nearby gives us an ordered, encrypted byte pipe and nothing else, so Beam layers its own
 * sequence on top: prove the tap, declare the payload, wait for the human, then stream.
 *
 * ```
 *   sender                         receiver
 *     |  AUTH(proof)                  |
 *     |  <-- AUTH_OK | AUTH_FAIL      |
 *     |  MANIFEST(json)               |
 *     |  <-- ACCEPT | DECLINE         |   (the user's answer)
 *     |  FILE_HEADER(i) + stream      |   repeated per file
 *     |  DONE                         |
 * ```
 */
internal object BeamFrame {

    const val AUTH: Byte = 0x01
    const val AUTH_OK: Byte = 0x02
    const val AUTH_FAIL: Byte = 0x03
    const val MANIFEST: Byte = 0x04
    const val ACCEPT: Byte = 0x05
    const val DECLINE: Byte = 0x06
    const val FILE_HEADER: Byte = 0x07
    const val DONE: Byte = 0x08

    fun of(type: Byte, body: ByteArray = ByteArray(0)): ByteArray =
        ByteArray(1 + body.size).also {
            it[0] = type
            body.copyInto(it, 1)
        }

    fun typeOf(frame: ByteArray): Byte? = frame.firstOrNull()

    fun bodyOf(frame: ByteArray): ByteArray =
        if (frame.size <= 1) ByteArray(0) else frame.copyOfRange(1, frame.size)

    fun fileHeader(index: Int): ByteArray = of(
        FILE_HEADER,
        byteArrayOf(
            (index ushr 24).toByte(),
            (index ushr 16).toByte(),
            (index ushr 8).toByte(),
            index.toByte(),
        ),
    )

    fun fileIndexOf(frame: ByteArray): Int? {
        val body = bodyOf(frame)
        if (body.size != 4) return null
        return ((body[0].toInt() and 0xFF) shl 24) or
            ((body[1].toInt() and 0xFF) shl 16) or
            ((body[2].toInt() and 0xFF) shl 8) or
            (body[3].toInt() and 0xFF)
    }
}
