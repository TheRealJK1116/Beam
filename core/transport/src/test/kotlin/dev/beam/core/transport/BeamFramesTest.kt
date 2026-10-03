package dev.beam.core.transport

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * The stream framing the Wi-Fi Direct transport runs on. A socket gives no message boundaries,
 * so these are the rules that keep a beam from desynchronising — including what happens when
 * the peer is truncated, hostile, or simply gone.
 */
class BeamFramesTest {

    private fun roundTrip(type: Byte, body: ByteArray): BeamFrames.Frame? {
        val buffer = ByteArrayOutputStream()
        BeamFrames.write(buffer, type, body)
        return BeamFrames.read(ByteArrayInputStream(buffer.toByteArray()))
    }

    @Test
    fun `a frame round trips`() {
        val frame = roundTrip(BeamFrames.MANIFEST, "hello".toByteArray())
        assertThat(frame?.type).isEqualTo(BeamFrames.MANIFEST)
        assertThat(frame?.body?.toString(Charsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    fun `an empty body round trips`() {
        val frame = roundTrip(BeamFrames.DONE, ByteArray(0))
        assertThat(frame?.type).isEqualTo(BeamFrames.DONE)
        assertThat(frame?.body).isEmpty()
    }

    @Test
    fun `several frames read back in order`() {
        val buffer = ByteArrayOutputStream()
        BeamFrames.write(buffer, BeamFrames.AUTH, byteArrayOf(1))
        BeamFrames.write(buffer, BeamFrames.MANIFEST, byteArrayOf(2))
        BeamFrames.write(buffer, BeamFrames.DONE)

        val input = ByteArrayInputStream(buffer.toByteArray())
        assertThat(BeamFrames.read(input)?.type).isEqualTo(BeamFrames.AUTH)
        assertThat(BeamFrames.read(input)?.type).isEqualTo(BeamFrames.MANIFEST)
        assertThat(BeamFrames.read(input)?.type).isEqualTo(BeamFrames.DONE)
        assertThat(BeamFrames.read(input)).isNull()
    }

    @Test
    fun `a clean end of stream reads as null rather than throwing`() {
        assertThat(BeamFrames.read(ByteArrayInputStream(ByteArray(0)))).isNull()
    }

    @Test
    fun `a truncated frame throws instead of returning a short body`() {
        val buffer = ByteArrayOutputStream()
        BeamFrames.write(buffer, BeamFrames.MANIFEST, ByteArray(64))
        val truncated = buffer.toByteArray().copyOfRange(0, 20)

        runCatching { BeamFrames.read(ByteArrayInputStream(truncated)) }
            .onSuccess { error("expected a failure, got $it") }
            .onFailure { assertThat(it).isInstanceOf(EOFException::class.java) }
    }

    @Test
    fun `a peer cannot make us allocate an unbounded buffer`() {
        // Type byte, then a declared length of 2 GB.
        val hostile = byteArrayOf(BeamFrames.MANIFEST, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        runCatching { BeamFrames.read(ByteArrayInputStream(hostile)) }
            .onSuccess { error("expected the oversized frame to be refused") }
            .onFailure { assertThat(it).isInstanceOf(IllegalStateException::class.java) }
    }

    @Test
    fun `writing an oversized body is refused at the source`() {
        runCatching { BeamFrames.write(ByteArrayOutputStream(), BeamFrames.MANIFEST, ByteArray(BeamFrames.MAX_BODY_BYTES + 1)) }
            .onSuccess { error("expected a refusal") }
            .onFailure { assertThat(it).isInstanceOf(IllegalArgumentException::class.java) }
    }

    @Test
    fun `file headers round trip, including large files and high indices`() {
        listOf(0 to 0L, 1 to 4_194_304L, 255 to 10_737_418_240L, 70_000 to 1L).forEach { (index, size) ->
            assertThat(BeamFrames.parseFileHeader(BeamFrames.fileHeader(index, size)))
                .isEqualTo(index to size)
        }
    }

    @Test
    fun `a malformed file header is rejected`() {
        assertThat(BeamFrames.parseFileHeader(ByteArray(4))).isNull()
        assertThat(BeamFrames.parseFileHeader(ByteArray(13))).isNull()
    }

    @Test
    fun `copyExactly moves exactly the declared count and leaves the rest`() {
        val source = ByteArrayInputStream(ByteArray(100) { it.toByte() })
        val sink = ByteArrayOutputStream()

        BeamFrames.copyExactly(source, sink, 60)

        assertThat(sink.size()).isEqualTo(60)
        // The remaining 40 bytes must still be there for the next frame.
        assertThat(source.available()).isEqualTo(40)
    }

    @Test
    fun `copyExactly reports monotonic progress ending at the total`() {
        val seen = mutableListOf<Long>()
        BeamFrames.copyExactly(
            ByteArrayInputStream(ByteArray(10_000)),
            ByteArrayOutputStream(),
            10_000,
            bufferSize = 1_000,
        ) { seen += it }

        assertThat(seen).isInOrder()
        assertThat(seen.last()).isEqualTo(10_000)
    }

    @Test
    fun `copyExactly throws when the peer sends fewer bytes than promised`() {
        runCatching {
            BeamFrames.copyExactly(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 100)
        }
            .onSuccess { error("expected a failure") }
            .onFailure { assertThat(it).isInstanceOf(EOFException::class.java) }
    }
}
