package dev.beam.transport.nearby

import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionAuth
import dev.beam.core.session.SessionToken
import dev.beam.core.transport.BeamFrames
import org.junit.Test

/**
 * The control protocol inside a Nearby connection. These are the parts that can be checked
 * without two phones and a radio; the connection handling itself needs hardware.
 */
class NearbyProtocolTest {

    @Test
    fun `a frame round trips its type and body`() {
        val frame = BeamFrames.of(BeamFrames.AUTH, byteArrayOf(1, 2, 3))
        assertThat(BeamFrames.typeOf(frame)).isEqualTo(BeamFrames.AUTH)
        assertThat(BeamFrames.bodyOf(frame)).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `a bodyless frame has an empty body rather than failing`() {
        val frame = BeamFrames.of(BeamFrames.DONE)
        assertThat(BeamFrames.typeOf(frame)).isEqualTo(BeamFrames.DONE)
        assertThat(BeamFrames.bodyOf(frame)).isEmpty()
    }

    @Test
    fun `an empty payload yields no type instead of an index crash`() {
        assertThat(BeamFrames.typeOf(ByteArray(0))).isNull()
        assertThat(BeamFrames.bodyOf(ByteArray(0))).isEmpty()
    }

    @Test
    fun `a digest names the file it belongs to`() {
        // Nearby delivers frames and streams through separate callbacks, so a digest that
        // did not identify its file could be matched to the wrong one.
        val digest = ByteArray(BeamFrames.DIGEST_BYTES) { it.toByte() }
        val (index, decoded) = BeamFrames.parseIndexedDigest(BeamFrames.indexedDigest(7, digest))!!
        assertThat(index).isEqualTo(7)
        assertThat(decoded).isEqualTo(digest)
    }

    @Test
    fun `a malformed indexed digest is rejected rather than truncated`() {
        assertThat(BeamFrames.parseIndexedDigest(ByteArray(10))).isNull()
        assertThat(BeamFrames.parseIndexedDigest(ByteArray(4 + BeamFrames.DIGEST_BYTES + 1))).isNull()
    }

    @Test
    fun `the auth context binds a proof to one connection`() {
        val token = SessionToken.mint(DeviceInfo("r", "R"), listOf(TransportId.NEARBY))

        val contextA = NearbyTransport.authContext(token, "1234")
        val contextB = NearbyTransport.authContext(token, "5678")
        assertThat(contextA).isNotEqualTo(contextB)

        // A proof captured off one connection must not verify against another.
        val proof = SessionAuth.proofFor(token, contextA)
        assertThat(SessionAuth.verify(token, contextA, proof)).isTrue()
        assertThat(SessionAuth.verify(token, contextB, proof)).isFalse()
    }

    @Test
    fun `the auth context also binds to the session, not just the digits`() {
        val a = SessionToken.mint(DeviceInfo("r", "R"), listOf(TransportId.NEARBY))
        val b = SessionToken.mint(DeviceInfo("r", "R"), listOf(TransportId.NEARBY))
        assertThat(NearbyTransport.authContext(a, "1234"))
            .isNotEqualTo(NearbyTransport.authContext(b, "1234"))
    }

    @Test
    fun `a bodyless frame survives the message envelope`() {
        val frame = BeamFrames.of(BeamFrames.DONE)
        assertThat(BeamFrames.typeOf(frame)).isEqualTo(BeamFrames.DONE)
        assertThat(BeamFrames.bodyOf(frame)).isEmpty()
    }

    @Test
    fun `an AUTH body carries the proof then the senders identity`() {
        val token = SessionToken.mint(DeviceInfo("r", "R"), listOf(TransportId.NEARBY))
        val proof = SessionAuth.proofFor(token, NearbyTransport.authContext(token, "0000"))
        val body = proof + "sender-abc".toByteArray(Charsets.UTF_8)

        assertThat(body.copyOfRange(0, 32)).isEqualTo(proof)
        assertThat(body.copyOfRange(32, body.size).toString(Charsets.UTF_8)).isEqualTo("sender-abc")
    }
}
