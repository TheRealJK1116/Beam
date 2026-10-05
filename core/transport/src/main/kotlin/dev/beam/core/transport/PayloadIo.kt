package dev.beam.core.transport

import dev.beam.core.model.FileRef
import dev.beam.core.model.PayloadManifest
import java.io.InputStream
import java.io.OutputStream

/**
 * Where a transport reads outgoing bytes from. Implemented over `ContentResolver` in the app;
 * implemented over an in-memory map in tests and in the simulation tools, which is why the
 * transports never touch `ContentResolver` themselves.
 */
fun interface PayloadSource {
    /** Caller closes the stream. Throws [java.io.IOException] if the URI is gone. */
    suspend fun open(ref: FileRef): InputStream
}

/**
 * Where a transport writes incoming bytes to. The receiver, not the sender, decides the final
 * location — a sender cannot aim a beam at an arbitrary path.
 */
interface PayloadSink {

    /**
     * Opens a destination for one manifest entry, starting from nothing.
     *
     * [peerStableId] is taken here rather than held as state on the sink so that resume
     * bookkeeping has the peer it needs without a mutable field that two transfers could race
     * over.
     */
    suspend fun create(entry: PayloadManifest.Entry, peerStableId: String = ""): Destination

    /**
     * How much of [entry] is already on disk from an interrupted attempt by [peerStableId],
     * or zero if there is nothing to resume.
     *
     * Keyed by the peer as well as the file so that two devices sending a file with the same
     * name and size cannot be confused for one another mid-transfer.
     */
    suspend fun heldBytes(entry: PayloadManifest.Entry, peerStableId: String): Long = 0L

    /** Reopens a partial for appending. Null when there is nothing held. */
    suspend fun resume(entry: PayloadManifest.Entry, peerStableId: String): Resumed? = null

    /** Stores a text or URL payload and returns something the UI can open. */
    suspend fun acceptText(manifest: PayloadManifest, text: String): String

    /**
     * An open destination.
     *
     * The three endings are deliberately distinct. [commit] publishes the file and clears any
     * resume state; [discard] deletes it, for a decline or a failed integrity check where
     * what is on disk is known to be wrong; [retain] keeps it and records how far it got, for
     * a dropped connection where the bytes so far are perfectly good and worth resuming.
     * Treating a dropped connection as a discard is what makes a large transfer start over
     * from nothing.
     */
    interface Destination {
        val stream: OutputStream
        suspend fun commit(): String
        suspend fun discard()
        suspend fun retain(bytesWritten: Long) = discard()
    }

    /** A destination reopened partway through, with the bytes already held re-readable. */
    interface Resumed : Destination {
        val bytesHeld: Long

        /** The bytes already on disk, so the receiver can fold them into its digest. */
        suspend fun openHeld(): java.io.InputStream
    }
}
