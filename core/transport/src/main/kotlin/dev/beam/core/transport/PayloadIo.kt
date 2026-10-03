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

    /** Opens a destination for one manifest entry. */
    suspend fun create(entry: PayloadManifest.Entry): Destination

    /** Stores a text or URL payload and returns something the UI can open. */
    suspend fun acceptText(manifest: PayloadManifest, text: String): String

    /**
     * An open destination. [commit] publishes it (makes it visible in the gallery, etc.) and
     * returns its final URI; [discard] removes a partial file after a failure.
     */
    interface Destination {
        val stream: OutputStream
        suspend fun commit(): String
        suspend fun discard()
    }
}
