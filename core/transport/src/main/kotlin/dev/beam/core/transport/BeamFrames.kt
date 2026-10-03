package dev.beam.core.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Beam's control protocol, and how it is framed over a plain byte stream.
 *
 * The sequence is the same whichever backend carries it — prove the tap, declare the payload,
 * wait for the human, stream the bytes — so it lives here rather than inside one transport.
 * Nearby Connections supplies its own message boundaries and uses only the type constants;
 * Wi-Fi Direct has a raw socket and uses the framing too.
 *
 * ```
 *   sender                         receiver
 *     |  AUTH(proof ‖ stableId)       |
 *     |  <-- AUTH_OK | AUTH_FAIL      |
 *     |  MANIFEST(json)               |
 *     |  <-- ACCEPT | DECLINE         |   the user's answer
 *     |  FILE_HEADER(index, size)     |   repeated per file,
 *     |  <raw bytes, exactly size>    |   body sent unframed so it never buffers
 *     |  DONE                         |
 * ```
 */
object BeamFrames {

    const val AUTH: Byte = 0x01
    const val AUTH_OK: Byte = 0x02
    const val AUTH_FAIL: Byte = 0x03
    const val MANIFEST: Byte = 0x04
    const val ACCEPT: Byte = 0x05
    const val DECLINE: Byte = 0x06
    const val FILE_HEADER: Byte = 0x07
    const val DONE: Byte = 0x08

    /** SHA-256: the fixed-size prefix of an AUTH frame, followed by the sender's stable id. */
    const val PROOF_BYTES = 32

    /** Nothing legitimate approaches this; it exists so a hostile peer cannot exhaust memory. */
    const val MAX_BODY_BYTES = 1024 * 1024

    data class Frame(val type: Byte, val body: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && type == other.type && body.contentEquals(other.body)

        override fun hashCode(): Int = 31 * type.toInt() + body.contentHashCode()
    }

    /** A control frame: one byte of type, a four-byte length, then the body. */
    fun write(out: OutputStream, type: Byte, body: ByteArray = ByteArray(0)) {
        require(body.size <= MAX_BODY_BYTES) { "frame body of ${body.size} bytes is too large" }
        val sink = DataOutputStream(out)
        sink.writeByte(type.toInt())
        sink.writeInt(body.size)
        sink.write(body)
        sink.flush()
    }

    /** Reads one control frame, or null at a clean end of stream. */
    fun read(input: InputStream): Frame? {
        val source = DataInputStream(input)
        val type = try {
            source.readByte()
        } catch (_: EOFException) {
            return null
        }
        val length = source.readInt()
        if (length !in 0..MAX_BODY_BYTES) {
            throw IllegalStateException("peer declared a $length byte frame")
        }
        val body = ByteArray(length)
        source.readFully(body)
        return Frame(type, body)
    }

    /** Four bytes of index, eight of size — packed by hand so the body is exactly 12 bytes. */
    fun fileHeader(index: Int, sizeBytes: Long): ByteArray {
        val body = ByteArray(12)
        body[0] = (index ushr 24).toByte()
        body[1] = (index ushr 16).toByte()
        body[2] = (index ushr 8).toByte()
        body[3] = index.toByte()
        for (i in 0 until 8) {
            body[4 + i] = (sizeBytes ushr (56 - 8 * i)).toByte()
        }
        return body
    }

    /** Returns index to size, or null if the header is malformed. */
    fun parseFileHeader(body: ByteArray): Pair<Int, Long>? {
        if (body.size != 12) return null
        var index = 0
        for (i in 0 until 4) index = (index shl 8) or (body[i].toInt() and 0xFF)
        var size = 0L
        for (i in 4 until 12) size = (size shl 8) or (body[i].toLong() and 0xFF)
        if (size < 0) return null
        return index to size
    }

    /**
     * Moves exactly [count] bytes, reporting cumulative progress. Used for file bodies, which
     * are written straight onto the socket rather than wrapped in a frame so that a large file
     * never has to fit in memory.
     */
    fun copyExactly(
        input: InputStream,
        output: OutputStream,
        count: Long,
        bufferSize: Int = 64 * 1024,
        onProgress: (Long) -> Unit = {},
    ) {
        val buffer = ByteArray(bufferSize)
        var remaining = count
        while (remaining > 0) {
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw EOFException("stream ended with $remaining bytes still expected")
            output.write(buffer, 0, read)
            remaining -= read
            onProgress(count - remaining)
        }
        output.flush()
    }
}
