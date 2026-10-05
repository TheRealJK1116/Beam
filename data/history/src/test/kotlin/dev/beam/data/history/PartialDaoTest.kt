package dev.beam.data.history

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bookkeeping behind resume. If this is wrong, an interrupted multi-gigabyte transfer
 * either starts from nothing or, worse, appends to the wrong file.
 */
@RunWith(RobolectricTestRunner::class)
class PartialDaoTest {

    private lateinit var db: HistoryDatabase
    private lateinit var dao: PartialDao

    @Before
    fun setUp() {
        db = HistoryDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        dao = db.partials()
    }

    @After
    fun tearDown() = db.close()

    private fun partial(
        peer: String = "peer-1",
        path: String = "holiday/beach.jpg",
        size: Long = 5_000_000,
        written: Long = 1_000_000,
    ) = PartialTransfer(
        key = PartialTransfer.keyFor(peer, path, size),
        uri = "content://media/x",
        bytesWritten = written,
        sizeBytes = size,
        peerStableId = peer,
        relativePath = path,
        updatedAt = 1_000,
    )

    @Test
    fun `a partial round trips`() = runTest {
        val p = partial()
        dao.put(p)
        assertThat(dao.find(p.key)).isEqualTo(p)
    }

    @Test
    fun `putting the same file again replaces rather than duplicates`() = runTest {
        dao.put(partial(written = 1_000))
        dao.put(partial(written = 2_000))
        assertThat(dao.find(partial().key)?.bytesWritten).isEqualTo(2_000)
    }

    @Test
    fun `the same file from two different peers is kept apart`() = runTest {
        // Otherwise a resume would append one device's bytes onto another's file.
        val a = partial(peer = "peer-a")
        val b = partial(peer = "peer-b")
        assertThat(a.key).isNotEqualTo(b.key)

        dao.put(a)
        dao.put(b)
        assertThat(dao.find(a.key)?.peerStableId).isEqualTo("peer-a")
        assertThat(dao.find(b.key)?.peerStableId).isEqualTo("peer-b")
    }

    @Test
    fun `a same-named file of a different size is a different partial`() = runTest {
        // Same name but a different length means it is not the same file.
        assertThat(partial(size = 100).key).isNotEqualTo(partial(size = 200).key)
    }

    @Test
    fun `the key is stable across calls`() = runTest {
        assertThat(PartialTransfer.keyFor("p", "a/b.jpg", 10))
            .isEqualTo(PartialTransfer.keyFor("p", "a/b.jpg", 10))
    }

    @Test
    fun `removing forgets the partial`() = runTest {
        val p = partial()
        dao.put(p)
        dao.remove(p.key)
        assertThat(dao.find(p.key)).isNull()
    }

    @Test
    fun `stale partials can be found for cleanup`() = runTest {
        dao.put(partial(path = "old.bin").copy(updatedAt = 500))
        dao.put(partial(path = "fresh.bin").copy(updatedAt = 5_000))

        val stale = dao.olderThan(1_000)
        assertThat(stale.map { it.relativePath }).containsExactly("old.bin")
    }

    @Test
    fun `an unknown key simply is not there`() = runTest {
        assertThat(dao.find("nothing")).isNull()
    }
}
