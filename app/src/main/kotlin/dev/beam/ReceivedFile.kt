package dev.beam

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap

/**
 * Working out what a received file is, so the right app can open it.
 *
 * Lives here rather than beside the history list because it is the kind of logic that is wrong
 * in ways no one notices: a file that will not open looks like the other app's fault, and the
 * first version of this was wrong for every file Beam ever received.
 */
object ReceivedFile {

    const val APK_MIME = "application/vnd.android.package-archive"

    /** What MediaStore falls back to when Beam had nothing better to record. */
    private const val UNKNOWN_MIME = "application/octet-stream"

    /**
     * The type to hand the system when opening [uri].
     *
     * The resolver is asked first and believed: these are MediaStore URIs, and Beam wrote the
     * type into MediaStore itself when it committed the file.
     *
     * What this replaced read the extension off the URI string. That cannot work — a MediaStore
     * URI is `content://media/external/downloads/1234` and has no extension in it — so every
     * received file was offered to the system as `* / *`. Most things still opened, because a
     * gallery or a text editor will take a punt on anything; an APK will not, because only the
     * installer handles those and it will not accept a wildcard.
     *
     * [label] is the filename, which is where an extension can actually be found.
     */
    fun mimeType(resolver: ContentResolver, uri: Uri, label: String): String {
        val stored = runCatching { resolver.getType(uri) }.getOrNull()
        if (!stored.isNullOrBlank() && stored != UNKNOWN_MIME) return stored

        val extension = label.substringAfterLast('.', "").lowercase()
        if (extension.isBlank()) return stored ?: "*/*"

        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }

        // A file that arrived without a usable type was stored as octet-stream, and an APK
        // saved that way cannot be opened at all: nothing claims the generic type, so the
        // installer never appears. The extension is the only evidence left.
        if (extension == "apk") return APK_MIME

        return stored ?: "*/*"
    }
}
