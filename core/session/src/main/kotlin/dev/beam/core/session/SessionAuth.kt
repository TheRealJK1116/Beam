package dev.beam.core.session

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Proof-of-tap.
 *
 * The receiver's advertisement is public: its name is the nonce, and anyone scanning can see it.
 * What a bystander cannot see is [SessionToken.secret], which only travelled over the ~1 cm NFC
 * link. So the sender proves it was the device that tapped by MAC-ing the session nonce together
 * with the endpoint it is connecting to, and the receiver checks that before accepting.
 *
 * This authenticates the tap. Confidentiality of the payload is the transport's job — Nearby
 * Connections encrypts its channel.
 */
object SessionAuth {

    private const val ALGORITHM = "HmacSHA256"

    /** Proof the sender attaches to its connection request. */
    fun proofFor(token: SessionToken, endpointId: String): ByteArray =
        mac(token.secret, token.nonce + endpointId.toByteArray(Charsets.UTF_8))

    /** Receiver side. Constant-time so a wrong guess leaks nothing through timing. */
    fun verify(token: SessionToken, endpointId: String, proof: ByteArray): Boolean =
        constantTimeEquals(proofFor(token, endpointId), proof)

    private fun mac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }.doFinal(message)

    internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
