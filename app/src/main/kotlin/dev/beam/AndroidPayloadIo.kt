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
class AndroidPayloadSink(context: Context) : PayloadSink {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override suspend fun create(entry: PayloadManifest.Entry): PayloadSink.Destination =
        withContext(Dispatchers.IO) {
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

            AndroidDestination(uri, resolver.openOutputStream(uri) ?: throw IOException("Could not write $name"))
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

    private inner class AndroidDestination(
        private val uri: Uri,
        override val stream: OutputStream,
    ) : PayloadSink.Destination {

        override suspend fun commit(): String = withContext(Dispatchers.IO) {
            runCatching { stream.close() }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri.toString()
        }

        override suspend fun discard() {
            withContext(Dispatchers.IO) {
                runCatching { stream.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }
}
