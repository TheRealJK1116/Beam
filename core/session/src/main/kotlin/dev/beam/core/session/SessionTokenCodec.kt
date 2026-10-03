package dev.beam.core.session

import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransferError
import dev.beam.core.model.TransportId

/**
 * Wire format for [SessionToken]. This is the whole protocol contract between two Beam
 * installs, so it is written by hand rather than by a serialization library: the layout is
 * fixed, every length is validated, and an unknown version fails loudly instead of being
 * guessed at.
 *
 * ```
 *   0   2   magic 'B','M'
 *   2   1   version
 *   3   1   flags            bit0 = autoAcceptHint
 *   4  16   nonce
 *  20  32   secret
 *  52   1   transport count
 *  53   n   transport wire ids
 *   .   1   device name length
 *   .   m   device name, UTF-8
 *   .   1   stable id length
 *   .   k   stable id, UTF-8
 * ```
 *
 * The result must fit one short APDU response, hence the tight string caps.
 */
object SessionTokenCodec {

    private const val MAGIC_0 = 'B'.code.toByte()
    private const val MAGIC_1 = 'M'.code.toByte()
    private const val FLAG_AUTO_ACCEPT = 0x01

    const val MAX_DEVICE_NAME_BYTES = 48
    const val MAX_STABLE_ID_BYTES = 24
    const val MAX_TRANSPORTS = 8

    /** Largest token the format can produce; stays inside a 253-byte short APDU response. */
    const val MAX_ENCODED_BYTES = 53 + MAX_TRANSPORTS + 1 + MAX_DEVICE_NAME_BYTES + 1 + MAX_STABLE_ID_BYTES

    fun encode(token: SessionToken): ByteArray {
        val name = token.device.displayName.truncateUtf8(MAX_DEVICE_NAME_BYTES)
        val stableId = token.device.stableId.toByteArray(Charsets.UTF_8)
        require(stableId.size <= MAX_STABLE_ID_BYTES) {
            "stableId is ${stableId.size} bytes, max $MAX_STABLE_ID_BYTES"
        }
        require(token.transports.size <= MAX_TRANSPORTS) {
            "too many transports: ${token.transports.size}"
        }

        val out = ByteArray(
            53 + token.transports.size + 1 + name.size + 1 + stableId.size
        )
        var i = 0
        out[i++] = MAGIC_0
        out[i++] = MAGIC_1
        out[i++] = token.version.toByte()
        out[i++] = (if (token.autoAcceptHint) FLAG_AUTO_ACCEPT else 0).toByte()
        token.nonce.copyInto(out, i); i += SessionToken.NONCE_BYTES
        token.secret.copyInto(out, i); i += SessionToken.SECRET_BYTES
        out[i++] = token.transports.size.toByte()
        token.transports.forEach { out[i++] = it.wireValue.toByte() }
        out[i++] = name.size.toByte()
        name.copyInto(out, i); i += name.size
        out[i++] = stableId.size.toByte()
        stableId.copyInto(out, i)
        return out
    }

    /**
     * Parses bytes that arrived over NFC from a device we have no reason to trust yet. Returns a
     * [TransferError] rather than throwing, because every failure here is something the UI has
     * to explain to the user.
     */
    fun decode(bytes: ByteArray): Result<SessionToken> {
        val reader = Reader(bytes)
        return runCatching {
            if (reader.remaining < 53) malformed("token is ${bytes.size} bytes, need at least 53")
            if (reader.byte() != MAGIC_0 || reader.byte() != MAGIC_1) malformed("bad magic")

            val version = reader.byte().toInt() and 0xFF
            if (version != SessionToken.PROTOCOL_VERSION) {
                throw TokenException(TransferError.VersionMismatch(SessionToken.PROTOCOL_VERSION, version))
            }

            val flags = reader.byte().toInt() and 0xFF
            val nonce = reader.bytes(SessionToken.NONCE_BYTES)
            val secret = reader.bytes(SessionToken.SECRET_BYTES)

            val transportCount = reader.byte().toInt() and 0xFF
            if (transportCount == 0) malformed("token offers no transports")
            if (transportCount > MAX_TRANSPORTS) malformed("token offers $transportCount transports")
            // Unknown ids are dropped, not fatal: a newer peer may offer backends we lack.
            val transports = (0 until transportCount)
                .map { reader.byte().toInt() and 0xFF }
                .mapNotNull(TransportId::fromWire)
            if (transports.isEmpty()) malformed("no transport in the token is supported here")

            val name = reader.lengthPrefixedString(MAX_DEVICE_NAME_BYTES, "device name")
            val stableId = reader.lengthPrefixedString(MAX_STABLE_ID_BYTES, "stable id")
            if (stableId.isBlank()) malformed("stable id is blank")

            SessionToken(
                version = version,
                nonce = nonce,
                secret = secret,
                transports = transports,
                device = DeviceInfo(
                    stableId = stableId,
                    displayName = name.ifBlank { DeviceInfo.Unknown.displayName },
                ),
                autoAcceptHint = flags and FLAG_AUTO_ACCEPT != 0,
            )
        }
    }

    /** Unwraps the typed error a failed [decode] carries. */
    fun errorOf(throwable: Throwable): TransferError = when (throwable) {
        is TokenException -> throwable.error
        else -> TransferError.MalformedToken(throwable.message ?: "Unreadable tap")
    }

    private fun malformed(reason: String): Nothing =
        throw TokenException(TransferError.MalformedToken(reason))

    class TokenException(val error: TransferError) : Exception(error.message)

    private class Reader(private val source: ByteArray) {
        private var pos = 0
        val remaining: Int get() = source.size - pos

        fun byte(): Byte {
            if (remaining < 1) malformed("truncated token")
            return source[pos++]
        }

        fun bytes(count: Int): ByteArray {
            if (remaining < count) malformed("truncated token: wanted $count, have $remaining")
            return source.copyOfRange(pos, pos + count).also { pos += count }
        }

        fun lengthPrefixedString(max: Int, field: String): String {
            val length = byte().toInt() and 0xFF
            if (length > max) malformed("$field is $length bytes, max $max")
            return bytes(length).toString(Charsets.UTF_8)
        }
    }
}

/** Trims to a byte budget without splitting a UTF-8 sequence or a surrogate pair. */
internal fun String.truncateUtf8(maxBytes: Int): ByteArray {
    val full = toByteArray(Charsets.UTF_8)
    if (full.size <= maxBytes) return full
    var end = maxBytes
    // Back off while we are inside a multi-byte sequence (continuation bytes are 10xxxxxx).
    while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end--
    return full.copyOfRange(0, end)
}
