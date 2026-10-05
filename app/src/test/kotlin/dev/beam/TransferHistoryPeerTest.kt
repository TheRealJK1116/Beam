package dev.beam

import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.BeamPayload
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.PayloadManifest
import dev.beam.core.model.TransferError
import dev.beam.core.model.TransferEvent
import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionToken
import dev.beam.core.transport.PayloadSink
import dev.beam.core.transport.PayloadSource
import dev.beam.core.transport.Transport
import dev.beam.core.transport.TransportRegistry
import dev.beam.data.history.TransferDao
import dev.beam.data.history.TransferRecord
import dev.beam.transport.fake.MemoryPayloadSink
import dev.beam.transport.fake.MemoryPayloadSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Who a history row says you beamed with.
 *
 * A failed *receive* used to be filed against this very phone: the fallback was the session
 * token's device, and the receiver mints that token to describe itself. The screen then
 * cheerfully reported that you had failed to receive something from yourself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferHistoryPeerTest {

    private val them = DeviceInfo("their-id", "Their phone")

    /** Fails after announcing a peer, like a connection dropping mid-transfer. */
    private class FailsAfterManifest(private val peer: DeviceInfo) : Transport {
        override val id = TransportId.FAKE
        override suspend fun isAvailable() = true

        override fun receive(
            token: SessionToken,
            sink: PayloadSink,
            decide: suspend (PayloadManifest, DeviceInfo) -> Boolean,
        ): Flow<TransferEvent> = flow {
            emit(TransferEvent.ManifestOffered(PayloadManifest(PayloadManifest.Kind.TEXT, text = "x"), peer))
            emit(TransferEvent.Failed(TransferError.Io("connection dropped")))
        }

        override fun send(token: SessionToken, payload: BeamPayload, source: PayloadSource) =
            flow<TransferEvent> { emit(TransferEvent.Failed(TransferError.Io("nope"))) }
    }

    /** Fails before any peer is known, like a radio that never finds anyone. */
    private class FailsImmediately : Transport {
        override val id = TransportId.FAKE
        override suspend fun isAvailable() = true

        override fun receive(
            token: SessionToken,
            sink: PayloadSink,
            decide: suspend (PayloadManifest, DeviceInfo) -> Boolean,
        ): Flow<TransferEvent> = flow { emit(TransferEvent.Failed(TransferError.DiscoveryTimeout())) }

        override fun send(token: SessionToken, payload: BeamPayload, source: PayloadSource) =
            flow<TransferEvent> { emit(TransferEvent.Failed(TransferError.DiscoveryTimeout())) }
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

    /** The token a receiver mints: it describes the receiver, never the sender. */
    private fun ourOwnToken() = SessionToken.mint(
        device = DeviceInfo("my-id", "My phone"),
        transports = listOf(TransportId.FAKE),
    )

    private fun coordinatorFor(
        transport: Transport,
        dao: RecordingDao,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = TransferCoordinator(
        registry = TransportRegistry(listOf(transport)),
        source = MemoryPayloadSource(),
        sink = MemoryPayloadSink(),
        dao = { dao },
        scope = scope,
    )

    @Test
    fun `a failed receive is filed against the sender, not ourselves`() = runTest {
        val dao = RecordingDao()
        coordinatorFor(FailsAfterManifest(them), dao, this)
            .beginReceive(ourOwnToken()) { true }
        advanceUntilIdle()

        val row = dao.inserted.single()
        assertThat(row.peerName).isEqualTo("Their phone")
        assertThat(row.peerStableId).isEqualTo("their-id")
        assertThat(row.peerName).isNotEqualTo("My phone")
    }

    @Test
    fun `a receive that fails before anyone is known admits it rather than naming us`() = runTest {
        val dao = RecordingDao()
        coordinatorFor(FailsImmediately(), dao, this)
            .beginReceive(ourOwnToken()) { true }
        advanceUntilIdle()

        val row = dao.inserted.single()
        // Better an honest "unknown" than confidently the wrong device.
        assertThat(row.peerStableId).isEqualTo(DeviceInfo.Unknown.stableId)
        assertThat(row.peerName).isNotEqualTo("My phone")
    }

    @Test
    fun `a failed send is still filed against the receiver, whom the token does describe`() = runTest {
        val dao = RecordingDao()
        // When sending, the token came from the other phone and names it.
        val theirToken = SessionToken.mint(them, listOf(TransportId.FAKE))
        coordinatorFor(FailsImmediately(), dao, this)
            .beginSend(theirToken, BeamPayload.Text("hi"))
        advanceUntilIdle()

        val row = dao.inserted.single()
        assertThat(row.outgoing).isTrue()
        assertThat(row.peerName).isEqualTo("Their phone")
    }

    @Test
    fun `one beam does not inherit the previous beam's peer`() = runTest {
        val dao = RecordingDao()
        val coordinator = coordinatorFor(FailsAfterManifest(them), dao, this)

        coordinator.beginReceive(ourOwnToken()) { true }
        advanceUntilIdle()

        // Second beam, nobody ever announced: must not reuse "Their phone".
        val second = coordinatorFor(FailsImmediately(), dao, this)
        second.beginReceive(ourOwnToken()) { true }
        advanceUntilIdle()

        assertThat(dao.inserted).hasSize(2)
        assertThat(dao.inserted[1].peerStableId).isEqualTo(DeviceInfo.Unknown.stableId)
    }
}
