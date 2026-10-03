package dev.beam.core.transport

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The AUTH frame's identity half.
 *
 * This exists because of a real bug: the receiver was showing *its own* device name as the
 * sender's. The session token describes whoever minted it — the receiver — so the sending
 * device's name has to travel over the wire, and must only be trusted once the proof beside
 * it verifies.
 */
class BeamFramesIdentityTest {

    private val proof = ByteArray(BeamFrames.PROOF_BYTES) { it.toByte() }

    @Test
    fun `an identity round trips alongside the proof`() {
        val encoded = BeamFrames.encodeAuth(proof, BeamFrames.Identity("abc123", "James's Pixel"))
        val (decodedProof, identity) = BeamFrames.decodeAuth(encoded)!!

        assertThat(decodedProof).isEqualTo(proof)
        assertThat(identity.stableId).isEqualTo("abc123")
        assertThat(identity.displayName).isEqualTo("James's Pixel")
    }

    @Test
    fun `the proof survives a name containing anything`() {
        val encoded = BeamFrames.encodeAuth(proof, BeamFrames.Identity("id", "Ünïcødé 📱 phone"))
        val (decodedProof, _) = BeamFrames.decodeAuth(encoded)!!
        assertThat(decodedProof).isEqualTo(proof)
    }

    @Test
    fun `a body with only a proof still parses, with no identity`() {
        val (decodedProof, identity) = BeamFrames.decodeAuth(proof)!!
        assertThat(decodedProof).isEqualTo(proof)
        assertThat(identity.stableId).isEmpty()
        assertThat(identity.displayName).isEmpty()
    }

    @Test
    fun `a body too short to hold a proof is rejected`() {
        assertThat(BeamFrames.decodeAuth(ByteArray(10))).isNull()
        assertThat(BeamFrames.decodeAuth(ByteArray(0))).isNull()
    }

    @Test
    fun `a lying length does not read past the end`() {
        val hostile = proof + byteArrayOf(99) + "short".toByteArray()
        val (decodedProof, identity) = BeamFrames.decodeAuth(hostile)!!
        assertThat(decodedProof).isEqualTo(proof)
        assertThat(identity.stableId).isEmpty()
    }

    @Test
    fun `control characters are stripped from a name before it reaches the screen`() {
        val encoded = BeamFrames.encodeAuth(proof, BeamFrames.Identity("id", "Evil\u0000\nPhone"))
        val (_, identity) = BeamFrames.decodeAuth(encoded)!!
        assertThat(identity.displayName).doesNotContain("\u0000")
        assertThat(identity.displayName).doesNotContain("\n")
    }

    @Test
    fun `a stable id is restricted to characters safe for a database key`() {
        val encoded = BeamFrames.encodeAuth(proof, BeamFrames.Identity("../../etc/passwd", "n"))
        val (_, identity) = BeamFrames.decodeAuth(encoded)!!
        assertThat(identity.stableId).doesNotContain("/")
        assertThat(identity.stableId).doesNotContain(".")
    }

    @Test
    fun `an absurdly long name cannot blow up the frame`() {
        val encoded = BeamFrames.encodeAuth(proof, BeamFrames.Identity("i".repeat(500), "n".repeat(500)))
        val (_, identity) = BeamFrames.decodeAuth(encoded)!!
        assertThat(identity.stableId.length).isAtMost(24)
        assertThat(identity.displayName.length).isAtMost(32)
    }
}
