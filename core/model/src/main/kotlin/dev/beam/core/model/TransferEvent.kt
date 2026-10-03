package dev.beam.core.model

/** Everything a transport reports back, in the order a healthy transfer produces it. */
sealed interface TransferEvent {

    /** The sender has a token and is looking for the receiver's endpoint. */
    data object Discovering : TransferEvent

    /** Endpoint found, connection being established and authenticated. */
    data object Connecting : TransferEvent

    /**
     * Receiver only: the peer authenticated and declared what it wants to send. The UI shows
     * the confirmation at this point; no body bytes have moved yet.
     */
    data class ManifestOffered(val manifest: PayloadManifest, val peer: DeviceInfo) : TransferEvent

    /** A new file in a multi-file transfer has begun, so the UI can name what is moving. */
    data class FileStarted(val index: Int, val name: String, val count: Int) : TransferEvent

    /** Both sides agreed; bytes are about to move. */
    data class Started(val totalBytes: Long, val peer: DeviceInfo) : TransferEvent

    data class Progress(val transferredBytes: Long, val totalBytes: Long) : TransferEvent {
        val fraction: Float
            get() = if (totalBytes <= 0) 0f else (transferredBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    }

    /** Terminal success. [receivedUris] is empty on the sending side. */
    data class Completed(
        val durationMillis: Long,
        val receivedUris: List<String> = emptyList(),
    ) : TransferEvent

    /** Terminal failure. */
    data class Failed(val error: TransferError) : TransferEvent
}

/** The ways a beam can fail, each mapped to a message the UI can show without a stack trace. */
sealed interface TransferError {
    val message: String

    data class NfcUnavailable(override val message: String = "NFC is off or unsupported") : TransferError
    data class TransportUnavailable(val transport: TransportId, override val message: String) : TransferError
    data class MalformedToken(override val message: String) : TransferError

    /** The reader and the tapped device do not speak a common protocol version. */
    data class VersionMismatch(val ours: Int, val theirs: Int) : TransferError {
        override val message: String = "Other device runs an incompatible version of Beam"
    }

    /** No endpoint carrying the token's nonce showed up before the deadline. */
    data class DiscoveryTimeout(override val message: String = "Could not find the other device") : TransferError

    /** The endpoint answered but could not prove it knew the token secret. */
    data class AuthenticationFailed(override val message: String = "Could not verify the other device") : TransferError

    data class Rejected(override val message: String = "The other device declined") : TransferError
    data class Cancelled(override val message: String = "Transfer cancelled") : TransferError
    data class Io(override val message: String) : TransferError

    /** The bytes arrived but did not hash to what the sender said they would. */
    data class IntegrityFailed(val fileName: String) : TransferError {
        override val message: String = "$fileName arrived damaged and was discarded"
    }
}
