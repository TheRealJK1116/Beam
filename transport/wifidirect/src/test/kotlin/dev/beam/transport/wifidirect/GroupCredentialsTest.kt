package dev.beam.transport.wifidirect

import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionToken
import org.junit.Test

/**
 * The credentials are the whole trick: both phones derive the same Wi-Fi group from the tap,
 * so there is no discovery and no dialog. That only holds if the derivation is deterministic,
 * unique per tap, and inside the platform's limits for a P2P network name and passphrase.
 */
class GroupCredentialsTest {

    private fun token() = SessionToken.mint(DeviceInfo("r", "Pixel"), listOf(TransportId.WIFI_DIRECT))

    @Test
    fun `both devices derive identical credentials from one token`() {
        val token = token()
        assertThat(GroupCredentials.of(token)).isEqualTo(GroupCredentials.of(token))
    }

    @Test
    fun `a different tap produces a different network`() {
        val a = GroupCredentials.of(token())
        val b = GroupCredentials.of(token())
        assertThat(a.networkName).isNotEqualTo(b.networkName)
        assertThat(a.passphrase).isNotEqualTo(b.passphrase)
    }

    @Test
    fun `the network name satisfies the Wi-Fi Direct format`() {
        val name = GroupCredentials.of(token()).networkName
        // SSID must begin DIRECT- followed by two characters, and fit 32 characters.
        assertThat(name).startsWith("DIRECT-")
        assertThat(name.length).isAtLeast(9)
        assertThat(name.length).isAtMost(32)
        assertThat(name.all { it.isLetterOrDigit() || it == '-' }).isTrue()
    }

    @Test
    fun `the passphrase is within the platform's length limits`() {
        val passphrase = GroupCredentials.of(token()).passphrase
        assertThat(passphrase.length).isAtLeast(8)
        assertThat(passphrase.length).isAtMost(63)
    }

    @Test
    fun `the passphrase is not the session secret`() {
        val token = token()
        val secretHex = token.secret.joinToString("") { "%02x".format(it) }
        val passphrase = GroupCredentials.of(token).passphrase

        // The secret also keys the proof-of-tap; a Wi-Fi passphrase is visible in system UI,
        // so the two must not be the same value.
        assertThat(passphrase).isNotEqualTo(secretHex)
        assertThat(secretHex).doesNotContain(passphrase)
    }

    @Test
    fun `the passphrase changes when only the secret changes`() {
        val base = token()
        val sameNonceDifferentSecret = base.copy(secret = ByteArray(32) { 9 })
        assertThat(GroupCredentials.of(base).passphrase)
            .isNotEqualTo(GroupCredentials.of(sameNonceDifferentSecret).passphrase)
    }

    @Test
    fun `the network name derives from the nonce, so it is public-safe`() {
        val token = token()
        val nonceHex = token.nonce.joinToString("") { "%02x".format(it) }
        assertThat(GroupCredentials.of(token).networkName).contains(nonceHex.substring(2, 12))
    }

    @Test
    fun `toString does not leak the passphrase`() {
        val credentials = GroupCredentials.of(token())
        assertThat(credentials.toString()).doesNotContain(credentials.passphrase)
    }
}
