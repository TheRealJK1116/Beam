package dev.beam.core.model

/**
 * Identifies a transfer backend. Encoded into the NFC session token so a sender knows which
 * backends the receiver is actually offering, which is what makes swapping Nearby Connections
 * for Wi-Fi Direct a runtime negotiation rather than a rewrite.
 */
enum class TransportId(val wireValue: Int) {
    NEARBY(1),
    WIFI_DIRECT(2),

    /** In-process loopback used by the developer simulation tools and by tests. */
    FAKE(99),
    ;

    companion object {
        fun fromWire(value: Int): TransportId? = entries.firstOrNull { it.wireValue == value }
    }
}
