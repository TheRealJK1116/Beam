package dev.beam.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Patterns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import androidx.documentfile.provider.DocumentFile
import dev.beam.core.model.BeamPayload
import dev.beam.core.model.FileRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns whatever the share sheet handed us into a [BeamPayload].
 *
 * Share intents are famously inconsistent — a "link" may arrive as plain text, a photo may
 * arrive with no display name and no size — so every field is resolved defensively and the
 * payload kinds stay coarse: text, URL, or files.
 */
object IntentPayloads {

    /**
     * Beam's own extra for "send this whole folder".
     *
     * A folder is not passed as a list of URIs: a few hundred files would overflow the binder
     * transaction. The tree URI travels instead and is walked here, off the main thread.
     */
    const val EXTRA_TREE_URI = "dev.beam.extra.TREE_URI"

    suspend fun from(context: Context, intent: Intent): BeamPayload? = withContext(Dispatchers.IO) {
        val tree = intent.getStringExtra(EXTRA_TREE_URI)
        if (tree != null) return@withContext folder(context, Uri.parse(tree))

        when (intent.action) {
            Intent.ACTION_SEND -> single(context, intent)
            Intent.ACTION_SEND_MULTIPLE -> multiple(context, intent)
            else -> null
        }
    }

    /**
     * Walks a picked folder recursively, keeping each file's path below the folder so the
     * receiver can rebuild the structure.
     *
     * Empty directories are not represented: Beam transfers files, and a directory with no
     * files in it has nothing to carry. Depth is capped so a pathological tree - or a
     * symlink loop surfaced through the documents provider - cannot spin forever.
     */
    private fun folder(context: Context, treeUri: Uri): BeamPayload? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val files = mutableListOf<FileRef>()
        collect(context, root, prefix = root.name?.takeIf { it.isNotBlank() } ?: "folder", depth = 0, into = files)
        return if (files.isEmpty()) null else BeamPayload.Files(files)
    }

    private fun collect(
        context: Context,
        dir: DocumentFile,
        prefix: String,
        depth: Int,
        into: MutableList<FileRef>,
    ) {
        if (depth > MAX_FOLDER_DEPTH || into.size >= MAX_FOLDER_FILES) return

        dir.listFiles().forEach { child ->
            if (into.size >= MAX_FOLDER_FILES) return
            val name = child.name ?: return@forEach
            val path = "$prefix/$name"

            if (child.isDirectory) {
                collect(context, child, path, depth + 1, into)
            } else if (child.isFile) {
                // DocumentFile.length() is also 0 for providers with no size column.
                val length = child.length().takeIf { it > 0 } ?: runCatching {
                    context.contentResolver.openFileDescriptor(child.uri, "r")?.use { it.statSize }
                }.getOrNull() ?: 0L

                into += FileRef(
                    uri = child.uri.toString(),
                    name = name,
                    mimeType = child.type ?: "application/octet-stream",
                    sizeBytes = length,
                    relativePath = path,
                )
            }
        }
    }

    private const val MAX_FOLDER_DEPTH = 24
    private const val MAX_FOLDER_FILES = 2_000

    private fun single(context: Context, intent: Intent): BeamPayload? {
        // The typed overloads are API 33; Beam supports Android 12.
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (uri != null) return BeamPayload.Files(listOf(describe(context, uri)))

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        return when {
            text.isEmpty() -> null
            isUrl(text) -> BeamPayload.Url(text)
            else -> BeamPayload.Text(text)
        }
    }

    private fun multiple(context: Context, intent: Intent): BeamPayload? {
        val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        if (uris.isEmpty()) return null
        return BeamPayload.Files(uris.map { describe(context, it) })
    }

    /** A single-line string that is entirely one web URL. */
    private fun isUrl(text: String): Boolean =
        !text.contains(Regex("\\s")) && Patterns.WEB_URL.matcher(text).matches()

    private fun describe(context: Context, uri: Uri): FileRef {
        val resolver = context.contentResolver
        var name: String? = null
        var size = 0L

        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                    }
                }
        }

        // Many providers expose no SIZE column. Treating that as a zero-byte file means the
        // sender streams nothing, both sides hash nothing, the digests agree, and an empty
        // file is committed as "integrity verified". Fall back to the real descriptor.
        if (size <= 0L) {
            size = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull()?.coerceAtLeast(0L) ?: 0L
        }

        val mimeType = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.').orEmpty())
            ?: "application/octet-stream"

        return FileRef(
            uri = uri.toString(),
            name = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "shared_file",
            mimeType = mimeType,
            sizeBytes = size,
        )
    }
}

/** Human file sizes, used in the confirmation and the history list. */
fun Long.formatBytes(): String = when {
    this >= 1_000_000_000 -> "%.1f GB".format(this / 1_000_000_000.0)
    this >= 1_000_000 -> "%.1f MB".format(this / 1_000_000.0)
    this >= 1_000 -> "%.0f kB".format(this / 1_000.0)
    else -> "$this B"
}
