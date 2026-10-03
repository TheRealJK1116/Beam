package dev.beam.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The first frame of every transfer: what is coming, so the receiver can show a meaningful
 * confirmation ("Photo, 2.4 MB") before a single body byte moves, and can pre-create its
 * destination files.
 *
 * JSON rather than the hand-rolled binary of the session token codec because this frame is not
 * size-constrained and will grow fields; `ignoreUnknownKeys` lets a newer sender talk to an
 * older receiver.
 */
@Serializable
data class PayloadManifest(
    val kind: Kind,
    val text: String? = null,
    val files: List<Entry> = emptyList(),
) {
    @Serializable
    enum class Kind { TEXT, URL, FILES }

    @Serializable
    data class Entry(
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    val totalBytes: Long
        get() = when (kind) {
            Kind.TEXT, Kind.URL -> text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
            Kind.FILES -> files.sumOf { it.sizeBytes }
        }

    fun encode(): ByteArray = json.encodeToString(this).toByteArray(Charsets.UTF_8)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Caps how much a peer can make us buffer before we have agreed to anything. */
        const val MAX_ENCODED_BYTES = 256 * 1024

        fun of(payload: BeamPayload): PayloadManifest = when (payload) {
            is BeamPayload.Text -> PayloadManifest(Kind.TEXT, text = payload.text)
            is BeamPayload.Url -> PayloadManifest(Kind.URL, text = payload.url)
            is BeamPayload.Files -> PayloadManifest(
                kind = Kind.FILES,
                files = payload.files.map { Entry(it.safeName, it.mimeType, it.sizeBytes) },
            )
        }

        fun decode(bytes: ByteArray): Result<PayloadManifest> = runCatching {
            require(bytes.size <= MAX_ENCODED_BYTES) { "manifest is ${bytes.size} bytes" }
            val manifest = json.decodeFromString<PayloadManifest>(bytes.toString(Charsets.UTF_8))
            manifest.validate()
            manifest
        }
    }

    private fun validate() {
        when (kind) {
            Kind.TEXT, Kind.URL -> require(text != null) { "$kind manifest has no text" }
            Kind.FILES -> {
                require(files.isNotEmpty()) { "FILES manifest lists no files" }
                files.forEach {
                    require(it.sizeBytes >= 0) { "negative file size for ${it.name}" }
                    require(it.name.isNotBlank()) { "blank file name" }
                }
            }
        }
    }

    /**
     * Rebuilds a payload description on the receiving side. File URIs are left empty: the
     * receiver assigns its own destinations.
     */
    fun toIncomingPayload(): BeamPayload = when (kind) {
        Kind.TEXT -> BeamPayload.Text(text.orEmpty())
        Kind.URL -> BeamPayload.Url(text.orEmpty())
        Kind.FILES -> BeamPayload.Files(
            files.map { FileRef(uri = "", name = it.name, mimeType = it.mimeType, sizeBytes = it.sizeBytes) }
        )
    }
}
