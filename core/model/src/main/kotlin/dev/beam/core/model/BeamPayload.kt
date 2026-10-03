package dev.beam.core.model

/**
 * What a beam carries.
 *
 * Deliberately free of Android types: a file is described by an opaque [FileRef.uri] string that
 * only the sending process has to be able to resolve. Transports move bytes and never interpret
 * the payload kind, so adding a kind here does not touch a transport.
 */
sealed interface BeamPayload {

    /** Total bytes the transfer will move, excluding the manifest frame. */
    val sizeBytes: Long

    /** Short human label for notifications and history rows. */
    val label: String

    data class Text(val text: String) : BeamPayload {
        override val sizeBytes: Long get() = text.toByteArray(Charsets.UTF_8).size.toLong()
        override val label: String get() = text.lineSequence().first().take(60)
    }

    data class Url(val url: String) : BeamPayload {
        override val sizeBytes: Long get() = url.toByteArray(Charsets.UTF_8).size.toLong()
        override val label: String get() = url
    }

    data class Files(val files: List<FileRef>) : BeamPayload {
        init {
            require(files.isNotEmpty()) { "Files payload needs at least one file" }
        }

        override val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
        override val label: String
            get() = if (files.size == 1) files.single().name else "${files.size} files"
    }
}

/**
 * A file the sender is offering. [uri] is meaningful only on the sending device; the receiver
 * writes to its own storage using [name] and [mimeType].
 */
data class FileRef(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
    init {
        require(name.isNotBlank()) { "File name must not be blank" }
        require(sizeBytes >= 0) { "File size must not be negative" }
    }

    /** Strips any path components a hostile sender may have smuggled into the name. */
    val safeName: String
        get() = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "beam_file" }
}
