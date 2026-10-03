package dev.beam.core.session

import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransferError
import dev.beam.core.model.TransportId
import org.junit.Test

class SessionTokenCodecTest {

    private val token = SessionToken(
        version = SessionToken.PROTOCOL_VERSION,
        nonce = ByteArray(16) { it.toByte() },
        secret = ByteArray(32) { (it * 7).toByte() },
        transports = listOf(TransportId.NEARBY, TransportId.WIFI_DIRECT),
        device = DeviceInfo(stableId = "abc123", displayName = "Pixel 9"),
        autoAcceptHint = true,
    )

    @Test
    fun `round trips every field`() {
        val decoded = SessionTokenCodec.decode(SessionTokenCodec.encode(token)).getOrThrow()
        assertThat(decoded).isEqualTo(token)
    }

    @Test
    fun `encoded token fits in one short APDU response`() {
        val maximal = token.copy(
            device = DeviceInfo(stableId = "x".repeat(24), displayName = "y".repeat(48)),
            transports = TransportId.entries,
        )
        val encoded = SessionTokenCodec.encode(maximal)
        assertThat(encoded.size).isAtMost(SessionTokenCodec.MAX_ENCODED_BYTES)
        // 253 bytes of data + 2 status bytes is the short-APDU ceiling.
        assertThat(encoded.size).isAtMost(253)
    }

    @Test
    fun `advertising name is derived from the nonce`() {
        val decoded = SessionTokenCodec.decode(SessionTokenCodec.encode(token)).getOrThrow()
        assertThat(decoded.advertisingName).isEqualTo(token.advertisingName)
        assertThat(decoded.advertisingName).startsWith(SessionToken.NAME_PREFIX)
    }

    @Test
    fun `rejects a token that is too short`() {
        val error = decodeError(ByteArray(10))
        assertThat(error).isInstanceOf(TransferError.MalformedToken::class.java)
    }

    @Test
    fun `rejects bad magic`() {
        val bytes = SessionTokenCodec.encode(token).also { it[0] = 'X'.code.toByte() }
        assertThat(decodeError(bytes)).isInstanceOf(TransferError.MalformedToken::class.java)
    }

    @Test
    fun `reports a version mismatch distinctly so the UI can explain it`() {
        val bytes = SessionTokenCodec.encode(token).also { it[2] = 99 }
        val error = decodeError(bytes)
        assertThat(error).isInstanceOf(TransferError.VersionMismatch::class.java)
        assertThat((error as TransferError.VersionMismatch).theirs).isEqualTo(99)
        assertThat(error.ours).isEqualTo(SessionToken.PROTOCOL_VERSION)
    }

    @Test
    fun `rejects a truncated tail`() {
        val full = SessionTokenCodec.encode(token)
        val truncated = full.copyOfRange(0, full.size - 3)
        assertThat(decodeError(truncated)).isInstanceOf(TransferError.MalformedToken::class.java)
    }

    @Test
    fun `rejects a token offering no transports`() {
        val bytes = SessionTokenCodec.encode(token.copy(transports = listOf(TransportId.NEARBY)))
        bytes[52] = 0
        assertThat(decodeError(bytes)).isInstanceOf(TransferError.MalformedToken::class.java)
    }

    @Test
    fun `drops unknown transport ids but keeps the known ones`() {
        val bytes = SessionTokenCodec.encode(token)
        bytes[53] = 77 // NEARBY -> unknown backend from a newer peer
        val decoded = SessionTokenCodec.decode(bytes).getOrThrow()
        assertThat(decoded.transports).containsExactly(TransportId.WIFI_DIRECT)
    }

    @Test
    fun `fails when no offered transport is understood`() {
        val bytes = SessionTokenCodec.encode(token)
        bytes[53] = 77
        bytes[54] = 78
        assertThat(decodeError(bytes)).isInstanceOf(TransferError.MalformedToken::class.java)
    }

    @Test
    fun `truncates an overlong device name on a multi-byte boundary`() {
        val emoji = "📱".repeat(40) // 4 bytes each, well over the 48-byte cap
        val encoded = SessionTokenCodec.encode(
            token.copy(device = DeviceInfo(stableId = "id", displayName = emoji))
        )
        val decoded = SessionTokenCodec.decode(encoded).getOrThrow()
        assertThat(decoded.device.displayName).isEqualTo("📱".repeat(12))
        assertThat(decoded.device.displayName).doesNotContain("�")
    }

    @Test
    fun `negotiate prefers our own transport ordering`() {
        assertThat(token.negotiate(listOf(TransportId.WIFI_DIRECT, TransportId.NEARBY)))
            .isEqualTo(TransportId.WIFI_DIRECT)
        assertThat(token.negotiate(listOf(TransportId.NEARBY, TransportId.WIFI_DIRECT)))
            .isEqualTo(TransportId.NEARBY)
        assertThat(token.negotiate(listOf(TransportId.FAKE))).isNull()
    }

    @Test
    fun `minted tokens are unique per tap`() {
        val a = SessionToken.mint(DeviceInfo("id", "Phone"), listOf(TransportId.NEARBY))
        val b = SessionToken.mint(DeviceInfo("id", "Phone"), listOf(TransportId.NEARBY))
        assertThat(a.nonce).isNotEqualTo(b.nonce)
        assertThat(a.secret).isNotEqualTo(b.secret)
    }

    @Test
    fun `toString never leaks the secret`() {
        assertThat(token.toString()).doesNotContain(token.secret.toHex())
    }

    private fun decodeError(bytes: ByteArray): TransferError =
        SessionTokenCodec.errorOf(SessionTokenCodec.decode(bytes).exceptionOrNull()!!)
}
