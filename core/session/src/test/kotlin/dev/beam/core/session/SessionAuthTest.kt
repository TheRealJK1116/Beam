package dev.beam.core.session

import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransportId
import org.junit.Test

class SessionAuthTest {

    private fun token() = SessionToken.mint(DeviceInfo("id", "Phone"), listOf(TransportId.NEARBY))

    @Test
    fun `a sender that tapped can prove it`() {
        val token = token()
        val proof = SessionAuth.proofFor(token, "ENDP")
        assertThat(SessionAuth.verify(token, "ENDP", proof)).isTrue()
    }

    @Test
    fun `a bystander who only saw the advertisement cannot`() {
        val real = token()
        // Same nonce (it is public, it is the endpoint name) but a guessed secret.
        val guess = real.copy(secret = ByteArray(32) { 0 })
        val forged = SessionAuth.proofFor(guess, "ENDP")
        assertThat(SessionAuth.verify(real, "ENDP", forged)).isFalse()
    }

    @Test
    fun `a proof is bound to the endpoint it was made for`() {
        val token = token()
        val proof = SessionAuth.proofFor(token, "ENDP_A")
        assertThat(SessionAuth.verify(token, "ENDP_B", proof)).isFalse()
    }

    @Test
    fun `a proof is bound to its session`() {
        val a = token()
        val b = token()
        assertThat(SessionAuth.verify(b, "ENDP", SessionAuth.proofFor(a, "ENDP"))).isFalse()
    }

    @Test
    fun `rejects a truncated proof rather than comparing a prefix`() {
        val token = token()
        val proof = SessionAuth.proofFor(token, "ENDP")
        assertThat(SessionAuth.verify(token, "ENDP", proof.copyOfRange(0, 16))).isFalse()
        assertThat(SessionAuth.verify(token, "ENDP", ByteArray(0))).isFalse()
    }

    @Test
    fun `constant time comparison still gets the answer right`() {
        assertThat(SessionAuth.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3))).isTrue()
        assertThat(SessionAuth.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4))).isFalse()
        assertThat(SessionAuth.constantTimeEquals(byteArrayOf(1), byteArrayOf(1, 2))).isFalse()
    }
}
