package dev.beam.core.model

/**
 * The other end of a beam, as shown to the user and as remembered for "auto-accept known
 * devices". [stableId] is derived from the peer's long-lived public identity rather than from the
 * per-tap nonce, so it survives across sessions.
 */
data class DeviceInfo(
    val stableId: String,
    val displayName: String,
) {
    init {
        require(stableId.isNotBlank()) { "stableId must not be blank" }
    }

    companion object {
        val Unknown = DeviceInfo(stableId = "unknown", displayName = "Nearby device")
    }
}
