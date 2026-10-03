package dev.beam.transport.nearby

import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionAuth
import dev.beam.core.session.SessionToken
import org.junit.Test

/**
 * The control protocol inside a Nearby connection. These are the parts that can be checked
 * without two phones and a radio; the connection handling itself needs hardware.
 */
class BeamFrameTest {

    @Test
    fun `a frame round trips its type and body`() {
        val frame = BeamFrame.of(BeamFrame.AUTH, byteArrayOf(1, 2, 3))
        assertThat(BeamFrame.typeOf(frame)).isEqualTo(BeamFrame.AUTH)
        assertThat(BeamFrame.bodyOf(frame)).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `a bodyless frame has an empty body rather than failing`() {
        val frame = BeamFrame.of(BeamFrame.DONE)
        assertThat(BeamFrame.typeOf(frame)).isEqualTo(BeamFrame.DONE)
        assertThat(BeamFrame.bodyOf(frame)).isEmpty()
    }

    @Test
    fun `an empty payload yields no type instead of an index crash`() {
        assertThat(BeamFrame.typeOf(ByteArray(0))).isNull()
        assertThat(BeamFrame.bodyOf(ByteArray(0))).isEmpty()
    }

    @Test
    fun `file indices round trip, including ones above a byte`() {
        listOf(0, 1, 255, 256, 70_000).forEach { index ->
            assertThat(BeamFrame.fileIndexOf(BeamFrame.fileHeader(index))).isEqualTo(index)
        }
    }

    @Test
    fun `a file header of the wrong length is rejected`() {
        assertThat(BeamFrame.fileIndexOf(BeamFrame.of(BeamFrame.FILE_HEADER, byteArrayOf(1, 2)))).isNull()
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
    fun `an AUTH body carries the proof then the senders identity`() {
        val token = SessionToken.mint(DeviceInfo("r", "R"), listOf(TransportId.NEARBY))
        val proof = SessionAuth.proofFor(token, NearbyTransport.authContext(token, "0000"))
        val body = proof + "sender-abc".toByteArray(Charsets.UTF_8)

        assertThat(body.copyOfRange(0, 32)).isEqualTo(proof)
        assertThat(body.copyOfRange(32, body.size).toString(Charsets.UTF_8)).isEqualTo("sender-abc")
    }
}
