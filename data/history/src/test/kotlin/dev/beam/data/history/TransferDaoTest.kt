package dev.beam.data.history

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.TransportId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransferDaoTest {

    private lateinit var db: HistoryDatabase
    private lateinit var dao: TransferDao

    @Before
    fun setUp() {
        db = HistoryDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        dao = db.transfers()
    }

    @After
    fun tearDown() = db.close()

    private fun record(
        peer: String = "peer-1",
        failure: String? = null,
        at: Long = 1_000,
    ) = TransferRecord(
        outgoing = false,
        label = "photo.jpg",
        kind = "FILES",
        sizeBytes = 2_400_000,
        peerStableId = peer,
        peerName = "Pixel",
        transport = TransportId.NEARBY,
        completedAt = at,
        durationMillis = 3_200,
        failureReason = failure,
    )

    @Test
    fun `recent returns newest first`() = runTest {
        dao.insert(record(at = 100))
        dao.insert(record(at = 300))
        dao.insert(record(at = 200))

        assertThat(dao.recent().first().map { it.completedAt })
            .containsExactly(300L, 200L, 100L).inOrder()
    }

    @Test
    fun `a peer becomes known only after a successful beam`() = runTest {
        dao.insert(record(peer = "p-fail", failure = "Declined"))
        assertThat(dao.isKnownPeer("p-fail")).isFalse()

        dao.insert(record(peer = "p-ok"))
        assertThat(dao.isKnownPeer("p-ok")).isTrue()
        assertThat(dao.isKnownPeer("never-seen")).isFalse()
    }

    @Test
    fun `the transport enum round trips through the database`() = runTest {
        val id = dao.insert(record().copy(transport = TransportId.WIFI_DIRECT))
        assertThat(dao.byId(id)?.transport).isEqualTo(TransportId.WIFI_DIRECT)
    }

    @Test
    fun `succeeded reflects the absence of a failure reason`() = runTest {
        val ok = dao.byId(dao.insert(record()))!!
        val bad = dao.byId(dao.insert(record(failure = "Cancelled")))!!
        assertThat(ok.succeeded).isTrue()
        assertThat(bad.succeeded).isFalse()
    }

    @Test
    fun `clear empties the list`() = runTest {
        dao.insert(record())
        dao.insert(record())
        dao.clear()
        assertThat(dao.recent().first()).isEmpty()
    }

    @Test
    fun `delete removes one row and leaves the rest`() = runTest {
        val keep = dao.insert(record(at = 1))
        val drop = dao.insert(record(at = 2))
        dao.delete(drop)
        assertThat(dao.recent().first().map { it.id }).containsExactly(keep)
    }
}
