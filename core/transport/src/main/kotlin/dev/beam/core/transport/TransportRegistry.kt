package dev.beam.core.transport

import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionToken

/**
 * The set of backends this install can use, in preference order.
 *
 * Swapping Nearby Connections for Wi-Fi Direct later means changing the list passed in here —
 * no call site changes, because callers only ever ask for "the transport for this token".
 */
class TransportRegistry(private val transports: List<Transport>) {

    init {
        require(transports.isNotEmpty()) { "at least one transport must be registered" }
        require(transports.map { it.id }.toSet().size == transports.size) {
            "duplicate transport ids: ${transports.map { it.id }}"
        }
    }

    operator fun get(id: TransportId): Transport? = transports.firstOrNull { it.id == id }

    /** Preference order, used when minting a token so we advertise what we can honour. */
    val ids: List<TransportId> get() = transports.map { it.id }

    /** The backends that are usable on this device right now. */
    suspend fun available(): List<Transport> = transports.filter { it.isAvailable() }

    /**
     * Picks the best backend the tapped device also offers. Null means the two installs share
     * no usable transport, which the UI reports rather than silently doing nothing.
     */
    suspend fun negotiate(token: SessionToken): Transport? {
        val usable = available()
        return usable.firstOrNull { it.id in token.transports }
    }
}
