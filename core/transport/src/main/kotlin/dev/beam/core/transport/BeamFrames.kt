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

    /**
     * Receiver to sender, last of all: every byte arrived and every file is committed.
     *
     * Without this the sender cannot know when it is safe to hang up. A write into a socket
     * returns once the bytes are buffered locally, not once they are delivered, so a sender
     * that closes as soon as its last write returns truncates the receiver mid-stream and
     * reports a transfer time that measures nothing but its own buffer.
     */
    const val RECEIVED: Byte = 0x09

    /**
     * Sender to receiver, after a file body: the SHA-256 of what was just sent.
     *
     * Sent as a trailer rather than declared in the manifest so that neither side has to read
     * the file twice - both digest as the bytes stream past, and compare at the end. A
     * mismatch means the file is discarded rather than quietly saved corrupted.
     */
    const val FILE_DIGEST: Byte = 0x0A

    /**
     * Receiver to sender, after accepting: how much of each file it already holds from an
     * interrupted attempt, so the sender can skip ahead instead of starting over.
     */
    const val RESUME_OFFER: Byte = 0x0B

    /**
     * Either side, at any point: this transfer is over and why.
     *
     * Distinct from AUTH_FAIL, which means only "you are not who you claim". Overloading that
     * for a mid-stream integrity failure left the sender reporting "the other phone did not
     * confirm" when the receiver knew precisely that a file had arrived damaged.
     */
    const val ABORT: Byte = 0x0C

    const val DIGEST_BYTES = 32

    /** SHA-256: the fixed-size prefix of an AUTH frame, followed by the sender's stable id. */
    const val PROOF_BYTES = 32

    /** Nothing legitimate approaches this; it exists so a hostile peer cannot exhaust memory. */
    const val MAX_BODY_BYTES = 1024 * 1024

    data class Frame(val type: Byte, val body: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && type == other.type && body.contentEquals(other.body)

        override fun hashCode(): Int = 31 * type.toInt() + body.contentHashCode()
    }

    /**
     * The same frame for a transport that already has message boundaries.
     *
     * Nearby Connections delivers each BYTES payload as a discrete message, so it needs only
     * the type byte and the body - the length prefix that [write] adds exists purely because
     * a socket has no boundaries of its own.
     */
    fun of(type: Byte, body: ByteArray = ByteArray(0)): ByteArray =
        ByteArray(1 + body.size).also {
            it[0] = type
            body.copyInto(it, 1)
        }

    fun typeOf(frame: ByteArray): Byte? = frame.firstOrNull()

    fun bodyOf(frame: ByteArray): ByteArray =
        if (frame.size <= 1) ByteArray(0) else frame.copyOfRange(1, frame.size)

    /** `index ‖ sha-256`, for a digest that must name the file it belongs to. */
    fun indexedDigest(index: Int, digest: ByteArray): ByteArray {
        val body = ByteArray(4 + digest.size)
        for (i in 0 until 4) body[i] = (index ushr (24 - 8 * i)).toByte()
        digest.copyInto(body, 4)
        return body
    }

    fun parseIndexedDigest(body: ByteArray): Pair<Int, ByteArray>? {
        if (body.size != 4 + DIGEST_BYTES) return null
        var index = 0
        for (i in 0 until 4) index = (index shl 8) or (body[i].toInt() and 0xFF)
        if (index < 0) return null
        return index to body.copyOfRange(4, body.size)
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

    /**
     * Who the sender is, carried in the AUTH frame's body after the proof.
     *
     * The receiver cannot infer this. The session token was minted by the receiver and
     * describes the receiver, so a receiver that reads a name out of the token is reading its
     * own — which is exactly the bug this exists to prevent. The only way the receiving phone
     * learns who is beaming at it is if the sender says so, and it is only believed once the
     * proof alongside it verifies.
     */
    data class Identity(val stableId: String, val displayName: String)

    private const val MAX_ID_BYTES = 24
    private const val MAX_NAME_BYTES = 48

    /** `proof ‖ idLength ‖ stableId ‖ displayName`. */
    fun encodeAuth(proof: ByteArray, identity: Identity): ByteArray {
        val id = identity.stableId.toByteArray(Charsets.UTF_8).take(MAX_ID_BYTES).toByteArray()
        val name = identity.displayName.toByteArray(Charsets.UTF_8).take(MAX_NAME_BYTES).toByteArray()
        return proof + byteArrayOf(id.size.toByte()) + id + name
    }

    /**
     * Splits an AUTH body. Returns null if it is too short to contain a proof; an older peer
     * that sends only `proof ‖ stableId` still parses, with an empty display name.
     */
    fun decodeAuth(body: ByteArray): Pair<ByteArray, Identity>? {
        if (body.size < PROOF_BYTES) return null
        val proof = body.copyOfRange(0, PROOF_BYTES)
        if (body.size == PROOF_BYTES) return proof to Identity("", "")

        val idLength = body[PROOF_BYTES].toInt() and 0xFF
        val idStart = PROOF_BYTES + 1
        if (idStart + idLength > body.size) return proof to Identity("", "")

        val id = body.copyOfRange(idStart, idStart + idLength).toString(Charsets.UTF_8)
        val name = body.copyOfRange(idStart + idLength, body.size).toString(Charsets.UTF_8)
        return proof to Identity(id.sanitizedId(), name.sanitizedName())
    }

    /** Both fields come from an unverified peer and end up on screen or in a database. */
    private fun String.sanitizedId(): String =
        filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_ID_BYTES)

    private fun String.sanitizedName(): String =
        filter { !it.isISOControl() }.trim().take(32)

    /**
     * Index, total size, and the offset this run starts from — 20 bytes, packed by hand.
     * [offset] is non-zero only when resuming a file the receiver already partly holds.
     */
    data class FileHeader(val index: Int, val sizeBytes: Long, val offset: Long) {
        val remaining: Long get() = sizeBytes - offset
    }

    fun fileHeader(index: Int, sizeBytes: Long, offset: Long = 0L): ByteArray {
        val body = ByteArray(20)
        for (i in 0 until 4) body[i] = (index ushr (24 - 8 * i)).toByte()
        for (i in 0 until 8) body[4 + i] = (sizeBytes ushr (56 - 8 * i)).toByte()
        for (i in 0 until 8) body[12 + i] = (offset ushr (56 - 8 * i)).toByte()
        return body
    }

    /** Returns the header, or null if it is malformed or self-contradictory. */
    fun parseFileHeader(body: ByteArray): FileHeader? {
        if (body.size != 20) return null
        var index = 0
        for (i in 0 until 4) index = (index shl 8) or (body[i].toInt() and 0xFF)
        var size = 0L
        for (i in 4 until 12) size = (size shl 8) or (body[i].toLong() and 0xFF)
        var offset = 0L
        for (i in 12 until 20) offset = (offset shl 8) or (body[i].toLong() and 0xFF)

        if (index < 0 || size < 0 || offset < 0 || offset > size) return null
        return FileHeader(index, size, offset)
    }

    /** `count ‖ (index, bytesHeld)*` — what the receiver already has of each file. */
    fun resumeOffer(held: Map<Int, Long>): ByteArray {
        val body = ByteArray(4 + held.size * 12)
        for (i in 0 until 4) body[i] = (held.size ushr (24 - 8 * i)).toByte()
        var at = 4
        held.forEach { (index, bytes) ->
            for (i in 0 until 4) body[at + i] = (index ushr (24 - 8 * i)).toByte()
            for (i in 0 until 8) body[at + 4 + i] = (bytes ushr (56 - 8 * i)).toByte()
            at += 12
        }
        return body
    }

    fun parseResumeOffer(body: ByteArray): Map<Int, Long> {
        if (body.size < 4) return emptyMap()
        var count = 0
        for (i in 0 until 4) count = (count shl 8) or (body[i].toInt() and 0xFF)
        // count is four peer-controlled bytes: 201 million would overflow `count * 12` to a
        // negative Int, sail past the bounds check and then index off the end of the array.
        if (count < 0 || 4L + count.toLong() * 12L > body.size.toLong()) return emptyMap()

        val held = mutableMapOf<Int, Long>()
        var at = 4
        repeat(count) {
            var index = 0
            for (i in 0 until 4) index = (index shl 8) or (body[at + i].toInt() and 0xFF)
            var bytes = 0L
            for (i in 0 until 8) bytes = (bytes shl 8) or (body[at + 4 + i].toLong() and 0xFF)
            if (index >= 0 && bytes > 0) held[index] = bytes
            at += 12
        }
        return held
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
        digest: java.security.MessageDigest? = null,
        onProgress: (Long) -> Unit = {},
    ) {
        val buffer = ByteArray(bufferSize)
        var remaining = count
        // Progress is reported per buffer, which at gigabyte scale is tens of thousands of
        // callbacks; the caller throttles rather than this allocating anything per chunk.
        while (remaining > 0) {
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw EOFException("stream ended with $remaining bytes still expected")
            output.write(buffer, 0, read)
            digest?.update(buffer, 0, read)
            remaining -= read
            onProgress(count - remaining)
        }
        output.flush()
    }

    /** Reads [count] bytes into the digest and discards them — used to re-hash a resumed file. */
    fun digestExactly(input: InputStream, count: Long, digest: java.security.MessageDigest, bufferSize: Int = 64 * 1024) {
        val buffer = ByteArray(bufferSize)
        var remaining = count
        while (remaining > 0) {
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw EOFException("stream ended with $remaining bytes still expected")
            digest.update(buffer, 0, read)
            remaining -= read
        }
    }

    fun sha256(): java.security.MessageDigest = java.security.MessageDigest.getInstance("SHA-256")
}
