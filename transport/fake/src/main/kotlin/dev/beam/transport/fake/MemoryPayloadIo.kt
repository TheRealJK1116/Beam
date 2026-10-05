package dev.beam.transport.fake

import dev.beam.core.model.FileRef
import dev.beam.core.model.PayloadManifest
import dev.beam.core.transport.PayloadSink
import dev.beam.core.transport.PayloadSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * A [PayloadSource] backed by bytes held in memory. Lets a simulated beam send a 4 MB "photo"
 * without touching storage or needing a real content URI.
 */
class MemoryPayloadSource(private val contents: Map<String, ByteArray> = emptyMap()) : PayloadSource {

    private val extra = ConcurrentHashMap<String, ByteArray>()

    fun put(uri: String, bytes: ByteArray): FileRef {
        extra[uri] = bytes
        return FileRef(uri = uri, name = uri.substringAfterLast('/'), mimeType = "application/octet-stream", sizeBytes = bytes.size.toLong())
    }

    override suspend fun open(ref: FileRef): InputStream {
        val bytes = extra[ref.uri] ?: contents[ref.uri] ?: throw IOException("no such fake uri: ${ref.uri}")
        return ByteArrayInputStream(bytes)
    }

    companion object {
        /** A deterministic blob of [size] bytes, standing in for a photo. */
        fun syntheticBytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }
    }
}

/**
 * A [PayloadSink] that keeps what it receives in memory, so a simulated beam can be asserted
 * on byte-for-byte and the Dev screen can show what "arrived" without writing to the gallery.
 */
class MemoryPayloadSink : PayloadSink {

    private val committed = ConcurrentHashMap<String, ByteArray>()
    private val discarded = mutableListOf<String>()

    val received: Map<String, ByteArray> get() = committed.toMap()
    val discardedNames: List<String> get() = synchronized(discarded) { discarded.toList() }

    override suspend fun create(
        entry: PayloadManifest.Entry,
        peerStableId: String,
    ): PayloadSink.Destination = MemoryDestination(entry.safePath)

    override suspend fun acceptText(manifest: PayloadManifest, text: String): String {
        committed["text"] = text.toByteArray(Charsets.UTF_8)
        return "memory://text"
    }

    private inner class MemoryDestination(private val name: String) : PayloadSink.Destination {
        private val buffer = ByteArrayOutputStream()
        override val stream: OutputStream get() = buffer

        override suspend fun commit(): String {
            committed[name] = buffer.toByteArray()
            return "memory://$name"
        }

        override suspend fun discard() {
            synchronized(discarded) { discarded += name }
        }

        /** The simulator does not persist, so a kept partial is just a discarded one. */
        override suspend fun retain(bytesWritten: Long) = discard()
    }
}
