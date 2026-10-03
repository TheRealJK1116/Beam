package dev.beam.transport.wifidirect

import dev.beam.core.session.SessionToken
import java.security.MessageDigest

/**
 * The Wi-Fi Direct group that a tap implies.
 *
 * This is what lets Beam skip everything Nearby needed Bluetooth for. Since Android 10 a P2P
 * group can be created with an explicit network name and passphrase, and a peer that already
 * knows them can join it directly. The NFC tap has just handed the sender a nonce and a
 * 32-byte secret, so both devices can derive the same credentials independently — no peer
 * discovery, no invitation dialog, no bonding, no Play Services.
 *
 * It also means the link is WPA2-protected by a key that only ever crossed the 1 cm NFC gap,
 * which is a stronger guarantee than Beam had over Nearby.
 */
data class GroupCredentials(
    val networkName: String,
    val passphrase: String,
) {
    companion object {
        /**
         * Wi-Fi Direct requires the SSID to begin "DIRECT-" followed by two characters; the
         * whole thing must fit 32 characters.
         */
        private const val PREFIX = "DIRECT-"

        fun of(token: SessionToken): GroupCredentials {
            val nonce = token.nonce.toHex()

            // "DIRECT-" + 2 + "Beam" + 10 = 23 characters, inside the 32-character ceiling.
            val networkName = PREFIX + nonce.substring(0, 2) + "Beam" + nonce.substring(2, 12)

            // Never the raw session secret: that is also the proof-of-tap key, and a Wi-Fi
            // passphrase is visible in system UI and logs. A separate derivation keeps the two
            // uses apart, so learning the passphrase does not let anyone forge a proof.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("beam-wifi-direct-v1".toByteArray(Charsets.UTF_8) + token.secret)

            // 32 hex characters, comfortably inside the 8..63 the platform allows.
            return GroupCredentials(networkName, digest.toHex().substring(0, 32))
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }

    /** Keep the passphrase out of logs. */
    override fun toString(): String = "GroupCredentials($networkName)"
}
