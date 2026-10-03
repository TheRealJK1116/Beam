package dev.beam.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Patterns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
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

    suspend fun from(context: Context, intent: Intent): BeamPayload? = withContext(Dispatchers.IO) {
        when (intent.action) {
            Intent.ACTION_SEND -> single(context, intent)
            Intent.ACTION_SEND_MULTIPLE -> multiple(context, intent)
            else -> null
        }
    }

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
