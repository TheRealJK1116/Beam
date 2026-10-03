package dev.beam.core.session

import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransportId
import java.security.SecureRandom

/**
 * Everything the sender learns from the tap.
 *
 * The receiver mints one of these per tap, starts advertising under [nonce], and hands the token
 * back over NFC. It is single-use and short-lived: the nonce names the advertisement, and
 * [secret] is the proof-of-tap that stops anyone who merely saw the advertisement from
 * connecting.
 */
data class SessionToken(
    val version: Int,
    val nonce: ByteArray,
    val secret: ByteArray,
    val transports: List<TransportId>,
    val device: DeviceInfo,
    val autoAcceptHint: Boolean = false,
) {
    init {
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes, was ${nonce.size}" }
        require(secret.size == SECRET_BYTES) { "secret must be $SECRET_BYTES bytes, was ${secret.size}" }
        require(transports.isNotEmpty()) { "token must offer at least one transport" }
    }

    /**
     * The advertising name the receiver publishes and the sender scans for. Short on purpose:
     * Nearby endpoint names are size-limited, and this is the only part that goes over the air
     * in the clear.
     */
    val advertisingName: String get() = NAME_PREFIX + nonce.toHex()

    /** Picks the best transport both sides support, preferring our own ordering. */
    fun negotiate(ourTransports: List<TransportId>): TransportId? =
        ourTransports.firstOrNull { it in transports }

    // ByteArray fields force hand-written equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionToken) return false
        return version == other.version &&
            nonce.contentEquals(other.nonce) &&
            secret.contentEquals(other.secret) &&
            transports == other.transports &&
            device == other.device &&
            autoAcceptHint == other.autoAcceptHint
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + transports.hashCode()
        result = 31 * result + device.hashCode()
        result = 31 * result + autoAcceptHint.hashCode()
        return result
    }

    /** Never log the secret. */
    override fun toString(): String =
        "SessionToken(v=$version, nonce=${nonce.toHex()}, transports=$transports, device=${device.displayName})"

    companion object {
        /**
         * 2 adds relative paths, per-file digests and resume offsets to the wire format.
         * Bumped rather than negotiated: two installs that disagree should say so plainly
         * instead of half-understanding each other mid-transfer.
         */
        const val PROTOCOL_VERSION = 2
        const val NONCE_BYTES = 16
        const val SECRET_BYTES = 32
        const val NAME_PREFIX = "beam:"

        private val random = SecureRandom()

        /** Mints a fresh single-use token. Called once per NFC tap, on the receiver. */
        fun mint(
            device: DeviceInfo,
            transports: List<TransportId>,
            autoAcceptHint: Boolean = false,
        ): SessionToken = SessionToken(
            version = PROTOCOL_VERSION,
            nonce = ByteArray(NONCE_BYTES).also(random::nextBytes),
            secret = ByteArray(SECRET_BYTES).also(random::nextBytes),
            transports = transports,
            device = device,
            autoAcceptHint = autoAcceptHint,
        )
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
