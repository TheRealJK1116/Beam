package dev.beam.transport.fake

import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.BeamPayload
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransferError
import dev.beam.core.model.TransferEvent
import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs a whole beam — token, proof-of-tap, manifest, confirmation, bytes, commit — with both
 * ends in this JVM. This is the end-to-end test the real transports cannot have without two
 * phones, and it is the same code path the Dev screen's "simulate a beam" button drives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeTransportLoopbackTest {

    private fun token(ether: FakeEther) = SessionToken.mint(
        device = DeviceInfo("receiver-1", "Simulated receiver"),
        transports = listOf(TransportId.FAKE),
    )

    private suspend fun beam(
        config: FakeTransportConfig = FakeTransportConfig(),
        payload: BeamPayload,
        source: MemoryPayloadSource,
        sink: MemoryPayloadSink = MemoryPayloadSink(),
        accept: Boolean = true,
    ): Pair<List<TransferEvent>, List<TransferEvent>> = coroutineScope {
        val ether = FakeEther()
        val token = token(ether)
        val receiver = FakeTransport(ether, config)
        val sender = FakeTransport(ether, config)

        val received = async { receiver.receive(token, sink) { _, _ -> accept }.toList() }
        val sent = async { sender.send(token, payload, source).toList() }
        sent.await() to received.await()
    }

    private val fastConfig = FakeTransportConfig(
        advertiseDelay = 0.milliseconds,
        discoveryDelay = 0.milliseconds,
        connectDelay = 0.milliseconds,
        throughputBytesPerSecond = Long.MAX_VALUE / 2,
    )

    @Test
    fun `a file arrives byte for byte`() = runTest {
        val bytes = MemoryPayloadSource.syntheticBytes(4 * 1024 * 1024)
        val source = MemoryPayloadSource()
        val ref = source.put("fake://photo.jpg", bytes).copy(name = "photo.jpg", mimeType = "image/jpeg")
        val sink = MemoryPayloadSink()

        val (sent, received) = beam(fastConfig, BeamPayload.Files(listOf(ref)), source, sink)

        assertThat(sent.last()).isInstanceOf(TransferEvent.Completed::class.java)
        assertThat(received.last()).isInstanceOf(TransferEvent.Completed::class.java)
        assertThat(sink.received.keys).containsExactly("photo.jpg")
        assertThat(sink.received["photo.jpg"]).isEqualTo(bytes)
    }

    @Test
    fun `the receiver is offered a manifest before any bytes move`() = runTest {
        val source = MemoryPayloadSource()
        val ref = source.put("fake://a.bin", MemoryPayloadSource.syntheticBytes(1024))
        val (_, received) = beam(fastConfig, BeamPayload.Files(listOf(ref)), source)

        val offerIndex = received.indexOfFirst { it is TransferEvent.ManifestOffered }
        val firstProgress = received.indexOfFirst { it is TransferEvent.Progress }
        assertThat(offerIndex).isAtLeast(0)
        assertThat(offerIndex).isLessThan(firstProgress)

        val offer = received[offerIndex] as TransferEvent.ManifestOffered
        assertThat(offer.manifest.totalBytes).isEqualTo(1024)
    }

    @Test
    fun `declining stops the transfer on both sides and writes nothing`() = runTest {
        val source = MemoryPayloadSource()
        val ref = source.put("fake://a.bin", MemoryPayloadSource.syntheticBytes(1024))
        val sink = MemoryPayloadSink()

        val (sent, received) = beam(fastConfig, BeamPayload.Files(listOf(ref)), source, sink, accept = false)

        assertThat((received.last() as TransferEvent.Failed).error)
            .isInstanceOf(TransferError.Rejected::class.java)
        assertThat((sent.last() as TransferEvent.Failed).error)
            .isInstanceOf(TransferError.Rejected::class.java)
        assertThat(sink.received).isEmpty()
    }

    @Test
    fun `a forged proof of tap is rejected`() = runTest {
        val source = MemoryPayloadSource()
        val ref = source.put("fake://a.bin", MemoryPayloadSource.syntheticBytes(64))
        val sink = MemoryPayloadSink()

        val (_, received) = beam(
            fastConfig.copy(forgeProof = true),
            BeamPayload.Files(listOf(ref)),
            source,
            sink,
        )

        assertThat((received.last() as TransferEvent.Failed).error)
            .isInstanceOf(TransferError.AuthenticationFailed::class.java)
        assertThat(sink.received).isEmpty()
    }

    @Test
    fun `a mid-transfer drop discards the partial file`() = runTest {
        val source = MemoryPayloadSource()
        val ref = source.put("fake://big.bin", MemoryPayloadSource.syntheticBytes(512 * 1024))
        val sink = MemoryPayloadSink()

        val (sent, received) = beam(
            fastConfig.copy(failAt = FakeStage.TRANSFER),
            BeamPayload.Files(listOf(ref)),
            source,
            sink,
        )

        assertThat((received.last() as TransferEvent.Failed).error).isInstanceOf(TransferError.Io::class.java)
        assertThat(sent.last()).isInstanceOf(TransferEvent.Failed::class.java)
        assertThat(sink.received).isEmpty()
        assertThat(sink.discardedNames).containsExactly("big.bin")
    }

    @Test
    fun `text payloads round trip`() = runTest {
        val sink = MemoryPayloadSink()
        val (sent, received) = beam(
            fastConfig,
            BeamPayload.Text("hello from the other phone"),
            MemoryPayloadSource(),
            sink,
        )

        assertThat(sent.last()).isInstanceOf(TransferEvent.Completed::class.java)
        assertThat(received.last()).isInstanceOf(TransferEvent.Completed::class.java)
        assertThat(sink.received["text"]?.toString(Charsets.UTF_8))
            .isEqualTo("hello from the other phone")
    }

    @Test
    fun `progress is monotonic and ends at the declared total`() = runTest {
        val size = 1024 * 1024
        val source = MemoryPayloadSource()
        val ref = source.put("fake://p.bin", MemoryPayloadSource.syntheticBytes(size))

        val (_, received) = beam(fastConfig, BeamPayload.Files(listOf(ref)), source)

        val progress = received.filterIsInstance<TransferEvent.Progress>()
        assertThat(progress).isNotEmpty()
        assertThat(progress.map { it.transferredBytes }).isInOrder()
        assertThat(progress.last().transferredBytes).isEqualTo(size.toLong())
        assertThat(progress.last().fraction).isEqualTo(1f)
    }

    @Test
    fun `a sender that finds no receiver times out instead of hanging`() = runTest {
        val ether = FakeEther()
        val sender = FakeTransport(
            ether,
            fastConfig.copy(discoveryTimeout = 50.milliseconds),
        )
        val token = SessionToken.mint(DeviceInfo("nobody", "Nobody"), listOf(TransportId.FAKE))

        val events = sender.send(token, BeamPayload.Text("hi"), MemoryPayloadSource()).toList()

        assertThat((events.last() as TransferEvent.Failed).error)
            .isInstanceOf(TransferError.DiscoveryTimeout::class.java)
    }

    @Test
    fun `an unavailable transport reports itself as such`() = runTest {
        assertThat(FakeTransport(FakeEther(), fastConfig.copy(available = false)).isAvailable()).isFalse()
        assertThat(FakeTransport(FakeEther(), fastConfig).isAvailable()).isTrue()
    }

    @Test
    fun `advertising is withdrawn once the receive flow ends`() = runTest {
        val ether = FakeEther()
        val token = SessionToken.mint(DeviceInfo("r", "R"), listOf(TransportId.FAKE))
        val receiver = FakeTransport(ether, fastConfig.copy(discoveryTimeout = 20.milliseconds))

        receiver.receive(token, MemoryPayloadSink()) { _, _ -> true }.toList()

        assertThat(ether.advertisedNames).doesNotContain(token.advertisingName)
    }
}
