package dev.beam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.beam.core.model.FileRef
import dev.beam.core.model.PayloadManifest
import dev.beam.core.transport.PayloadSink
import dev.beam.core.transport.PayloadSource
import dev.beam.data.history.PartialDao
import dev.beam.data.history.PartialTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Reads outgoing bytes out of whatever the share sheet handed us. */
class AndroidPayloadSource(context: Context) : PayloadSource {

    private val resolver = context.applicationContext.contentResolver

    override suspend fun open(ref: FileRef): InputStream = withContext(Dispatchers.IO) {
        resolver.openInputStream(Uri.parse(ref.uri))
            ?: throw IOException("Could not open ${ref.safeName}")
    }
}

/**
 * Writes incoming bytes into the user's own storage.
 *
 * Everything lands in a `Beam` folder under Downloads (or Pictures/Movies for media, so it
 * shows up in the gallery). The sender has no say in the destination — only in the file name,
 * which is stripped of any path separators first — and nothing is published until the bytes are
 * all there, so a failed beam leaves no half-written file visible.
 */
class AndroidPayloadSink(
    context: Context,
    /**
     * Where interrupted files are remembered. Supplied lazily because the database is opened
     * on first use and the sink is built during Application.onCreate, on the critical path of
     * answering a tap.
     */
    private val partials: () -> PartialDao,
) : PayloadSink {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** Peer is part of the key so two devices' same-named files cannot be mixed up. */
    private fun keyFor(entry: PayloadManifest.Entry, peerStableId: String) =
        PartialTransfer.keyFor(peerStableId, entry.safePath, entry.sizeBytes)

    override suspend fun heldBytes(entry: PayloadManifest.Entry, peerStableId: String): Long =
        withContext(Dispatchers.IO) {
            val record = partials().find(keyFor(entry, peerStableId)) ?: return@withContext 0L

            // Trust the file, not the bookkeeping: the row could outlive the bytes if the user
            // cleared storage, and resuming from a length that is not there would corrupt the
            // file in a way the digest would only catch at the very end.
            val actual = runCatching { lengthOf(Uri.parse(record.uri)) }.getOrNull()
            if (actual == null || actual <= 0L || actual != record.bytesWritten) {
                forget(record)
                0L
            } else {
                actual
            }
        }

    override suspend fun resume(entry: PayloadManifest.Entry, peerStableId: String): PayloadSink.Resumed? =
        withContext(Dispatchers.IO) {
            val record = partials().find(keyFor(entry, peerStableId)) ?: return@withContext null
            val uri = Uri.parse(record.uri)

            val actual = runCatching { lengthOf(uri) }.getOrNull() ?: 0L
            if (actual <= 0L || actual != record.bytesWritten) {
                forget(record)
                return@withContext null
            }

            // "wa" appends; anything else would truncate what we are trying to keep.
            val stream = runCatching { resolver.openOutputStream(uri, "wa") }.getOrNull()
                ?: run {
                    forget(record)
                    return@withContext null
                }

            ResumedDestination(uri, stream, actual, entry, peerStableId)
        }

    private fun lengthOf(uri: Uri): Long =
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L

    private suspend fun forget(record: PartialTransfer) {
        runCatching { resolver.delete(Uri.parse(record.uri), null, null) }
        runCatching { partials().remove(record.key) }
    }

    override suspend fun create(
        entry: PayloadManifest.Entry,
        peerStableId: String,
    ): PayloadSink.Destination = withContext(Dispatchers.IO) {
            // safePath has already had any "..", absolute roots and empty segments removed.
            val path = entry.safePath
            val name = path.substringAfterLast('/')
            val subFolder = path.substringBeforeLast('/', missingDelimiterValue = "")
            val collection = collectionFor(entry.mimeType)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType.ifBlank { "application/octet-stream" })
                // A folder arrives as a folder: its structure below the Beam directory is
                // recreated rather than flattened into a heap of loose files.
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    buildString {
                        append(relativePathFor(entry.mimeType))
                        if (subFolder.isNotEmpty()) append('/').append(subFolder)
                    },
                )
                // Hidden from the gallery until commit(), so a failed transfer never surfaces.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(collection, values)
                ?: throw IOException("Could not create a destination for $name")

            AndroidDestination(
                uri = uri,
                stream = resolver.openOutputStream(uri) ?: throw IOException("Could not write $name"),
                entry = entry,
                peerId = peerStableId,
            )
    }

    override suspend fun acceptText(manifest: PayloadManifest, text: String): String = text

    private fun collectionFor(mimeType: String): Uri = when {
        mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }

    private fun relativePathFor(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "${Environment.DIRECTORY_PICTURES}/Beam"
        mimeType.startsWith("video/") -> "${Environment.DIRECTORY_MOVIES}/Beam"
        mimeType.startsWith("audio/") -> "${Environment.DIRECTORY_MUSIC}/Beam"
        else -> "${Environment.DIRECTORY_DOWNLOADS}/Beam"
    }

    private open inner class AndroidDestination(
        protected val uri: Uri,
        override val stream: OutputStream,
        protected val entry: PayloadManifest.Entry,
        protected val peerId: String,
    ) : PayloadSink.Destination {

        /** Blank for an anonymous peer, in which case there is nothing to resume against. */
        protected val partialKey: String? =
            peerId.takeIf { it.isNotBlank() }?.let { PartialTransfer.keyFor(it, entry.safePath, entry.sizeBytes) }

        override suspend fun commit(): String = withContext(Dispatchers.IO) {
            runCatching { stream.close() }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            // The file is whole; there is nothing left to resume.
            partialKey?.let { runCatching { partials().remove(it) } }
            uri.toString()
        }

        override suspend fun discard() {
            withContext(Dispatchers.IO) {
                runCatching { stream.close() }
                runCatching { resolver.delete(uri, null, null) }
                partialKey?.let { runCatching { partials().remove(it) } }
            }
        }

        /**
         * The connection dropped but the bytes so far are good. Keep the pending item - still
         * invisible to the gallery - and record how far it got so the next attempt can pick up
         * rather than re-sending gigabytes.
         */
        override suspend fun retain(bytesWritten: Long) {
            withContext(Dispatchers.IO) {
                runCatching { stream.close() }
                val key = partialKey
                if (bytesWritten <= 0L || key == null) {
                    // Nothing worth keeping, or no peer to key it against.
                    runCatching { resolver.delete(uri, null, null) }
                    return@withContext
                }
                runCatching {
                    partials().put(
                        PartialTransfer(
                            key = key,
                            uri = uri.toString(),
                            bytesWritten = bytesWritten,
                            sizeBytes = entry.sizeBytes,
                            peerStableId = peerId,
                            relativePath = entry.safePath,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }

    }

    private inner class ResumedDestination(
        uri: Uri,
        stream: OutputStream,
        override val bytesHeld: Long,
        entry: PayloadManifest.Entry,
        peerId: String,
    ) : AndroidDestination(uri, stream, entry, peerId), PayloadSink.Resumed {

        override suspend fun openHeld(): java.io.InputStream = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri) ?: throw IOException("Could not re-read the partial file")
        }
    }

}
