package dev.beam

import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.BeamPayload
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.PayloadManifest
import dev.beam.core.model.TransferError
import dev.beam.core.model.TransferEvent
import dev.beam.core.model.TransportId
import dev.beam.core.session.HandshakeState
import dev.beam.core.session.SessionToken
import dev.beam.core.transport.PayloadSink
import dev.beam.core.transport.PayloadSource
import dev.beam.core.transport.Transport
import dev.beam.core.transport.TransportRegistry
import dev.beam.data.history.TransferDao
import dev.beam.data.history.TransferRecord
import dev.beam.transport.fake.FakeEther
import dev.beam.transport.fake.FakeTransport
import dev.beam.transport.fake.FakeTransportConfig
import dev.beam.transport.fake.MemoryPayloadSink
import dev.beam.transport.fake.MemoryPayloadSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * A transport can claim to be available and still fail the moment it runs — a Wi-Fi Direct
 * group that will not form, a Nearby call Play Services refuses. Both happened on real
 * hardware, so the coordinator has to survive it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferCoordinatorFallbackTest {

    /** Reports itself healthy, then fails as soon as it is asked to do anything. */
    private class BrokenTransport(override val id: TransportId) : Transport {
        var sendAttempts = 0

        override suspend fun isAvailable() = true

        override fun receive(
            token: SessionToken,
            sink: PayloadSink,
            decide: suspend (PayloadManifest, DeviceInfo) -> Boolean,
        ): Flow<TransferEvent> = flow {
            emit(TransferEvent.Discovering)
            emit(TransferEvent.Failed(TransferError.TransportUnavailable(id, "simulated group failure")))
        }

        override fun send(
            token: SessionToken,
            payload: BeamPayload,
            source: PayloadSource,
        ): Flow<TransferEvent> = flow {
            sendAttempts++
            emit(TransferEvent.Discovering)
            emit(TransferEvent.Failed(TransferError.TransportUnavailable(id, "simulated group failure")))
        }
    }

    private class RecordingDao : TransferDao {
        val inserted = mutableListOf<TransferRecord>()
        override suspend fun insert(record: TransferRecord): Long {
            inserted += record
            return inserted.size.toLong()
        }

        override fun recent(limit: Int): Flow<List<TransferRecord>> = flow { emit(inserted.toList()) }
        override suspend fun delete(id: Long) = Unit
        override suspend fun clear() = Unit
        override suspend fun isKnownPeer(stableId: String) = false
        override suspend fun byId(id: Long): TransferRecord? = inserted.getOrNull((id - 1).toInt())
    }

    private val fastFake = FakeTransportConfig(
        advertiseDelay = 0.milliseconds,
        discoveryDelay = 0.milliseconds,
        connectDelay = 0.milliseconds,
        throughputBytesPerSecond = Long.MAX_VALUE / 2,
    )

    private fun token(vararg transports: TransportId) = SessionToken.mint(
        device = DeviceInfo("receiver", "Their phone"),
        transports = transports.toList(),
    )

    @Test
    fun `a sender falls back to the next transport when the first fails before committing`() = runTest {
        val ether = FakeEther()
        val broken = BrokenTransport(TransportId.WIFI_DIRECT)
        val working = FakeTransport(ether, fastFake)
        val dao = RecordingDao()

        val coordinator = TransferCoordinator(
            registry = TransportRegistry(listOf(broken, working)),
            source = MemoryPayloadSource(),
            sink = MemoryPayloadSink(),
            dao = { dao },
            scope = this,
        )

        val token = token(TransportId.WIFI_DIRECT, TransportId.FAKE)
        // Something must be listening for the fallback to reach.
        val receiverSink = MemoryPayloadSink()
        val listener = backgroundScope.launch {
            FakeTransport(ether, fastFake).receive(token, receiverSink) { _, _ -> true }.collect {}
        }

        coordinator.beginSend(token, BeamPayload.Text("fallback please"))
        advanceUntilIdle()

        assertThat(broken.sendAttempts).isEqualTo(1)
        assertThat(coordinator.state.value).isInstanceOf(HandshakeState.Complete::class.java)
        assertThat(receiverSink.received).isNotEmpty()
        listener.cancel()
    }

    @Test
    fun `the history row records the transport that actually carried the beam`() = runTest {
        val ether = FakeEther()
        val dao = RecordingDao()
        val coordinator = TransferCoordinator(
            registry = TransportRegistry(listOf(BrokenTransport(TransportId.WIFI_DIRECT), FakeTransport(ether, fastFake))),
            source = MemoryPayloadSource(),
            sink = MemoryPayloadSink(),
            dao = { dao },
            scope = this,
        )

        val token = token(TransportId.WIFI_DIRECT, TransportId.FAKE)
        val listener = backgroundScope.launch {
            FakeTransport(ether, fastFake).receive(token, MemoryPayloadSink()) { _, _ -> true }.collect {}
        }

        coordinator.beginSend(token, BeamPayload.Text("hi"))
        advanceUntilIdle()

        assertThat(dao.inserted).hasSize(1)
        assertThat(dao.inserted.single().transport).isEqualTo(TransportId.FAKE)
        listener.cancel()
    }

    @Test
    fun `every transport failing is reported rather than retried forever`() = runTest {
        val first = BrokenTransport(TransportId.WIFI_DIRECT)
        val second = BrokenTransport(TransportId.NEARBY)
        val coordinator = TransferCoordinator(
            registry = TransportRegistry(listOf(first, second)),
            source = MemoryPayloadSource(),
            sink = MemoryPayloadSink(),
            dao = { RecordingDao() },
            scope = this,
        )

        coordinator.beginSend(token(TransportId.WIFI_DIRECT, TransportId.NEARBY), BeamPayload.Text("x"))
        advanceUntilIdle()

        assertThat(first.sendAttempts).isEqualTo(1)
        assertThat(second.sendAttempts).isEqualTo(1)
        assertThat(coordinator.state.value).isInstanceOf(HandshakeState.Failed::class.java)
    }

    @Test
    fun `a token sharing no transport with us fails immediately`() = runTest {
        val coordinator = TransferCoordinator(
            registry = TransportRegistry(listOf(BrokenTransport(TransportId.NEARBY))),
            source = MemoryPayloadSource(),
            sink = MemoryPayloadSink(),
            dao = { RecordingDao() },
            scope = this,
        )

        coordinator.beginSend(token(TransportId.WIFI_DIRECT), BeamPayload.Text("x"))
        advanceUntilIdle()

        val state = coordinator.state.value
        assertThat(state).isInstanceOf(HandshakeState.Failed::class.java)
        assertThat((state as HandshakeState.Failed).error)
            .isInstanceOf(TransferError.TransportUnavailable::class.java)
    }

    @Test
    fun `a receiver listening on several transports accepts whichever the sender reaches`() = runTest {
        val ether = FakeEther()
        val dao = RecordingDao()
        val receiverSink = MemoryPayloadSink()

        val coordinator = TransferCoordinator(
            // The broken one would win the preference order, but cannot carry anything.
            registry = TransportRegistry(listOf(BrokenTransport(TransportId.WIFI_DIRECT), FakeTransport(ether, fastFake))),
            source = MemoryPayloadSource(),
            sink = receiverSink,
            dao = { dao },
            scope = this,
        )

        val token = token(TransportId.WIFI_DIRECT, TransportId.FAKE)
        // Auto-accept, so the test exercises the transport race rather than the prompt.
        coordinator.beginReceive(token) { true }

        val sender = backgroundScope.launch {
            FakeTransport(ether, fastFake)
                .send(token, BeamPayload.Text("over the working one"), MemoryPayloadSource())
                .collect {}
        }
        advanceUntilIdle()

        assertThat(coordinator.state.value).isInstanceOf(HandshakeState.Complete::class.java)
        assertThat(receiverSink.received).isNotEmpty()
        sender.cancel()
    }
}
