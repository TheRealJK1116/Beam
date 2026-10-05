package dev.beam.data.history

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import java.security.MessageDigest

/**
 * A file that arrived partway and is worth finishing.
 *
 * Resume needs one durable fact per interrupted file — where its bytes are and how many
 * landed — because the alternative is re-sending gigabytes over a radio link that just proved
 * it can drop. The bytes themselves stay in their MediaStore pending item, invisible to the
 * gallery until committed, so an abandoned transfer never surfaces as a half a photo.
 */
@Entity(tableName = "partials", indices = [Index("updatedAt")])
data class PartialTransfer(
    /** Derived from the peer, the file's path and its size: see [keyFor]. */
    @PrimaryKey val key: String,

    /** The pending MediaStore item holding the bytes so far. */
    val uri: String,

    val bytesWritten: Long,
    val sizeBytes: Long,
    val peerStableId: String,
    val relativePath: String,
    val updatedAt: Long,
) {
    companion object {
        /**
         * Keyed on the peer as well as the file, so two devices sending a same-named file of
         * the same size cannot have their partials confused for one another.
         */
        fun keyFor(peerStableId: String, relativePath: String, sizeBytes: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$peerStableId|$relativePath|$sizeBytes".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}

@Dao
interface PartialDao {

    @Query("SELECT * FROM partials WHERE `key` = :key")
    suspend fun find(key: String): PartialTransfer?

    @Upsert
    suspend fun put(partial: PartialTransfer)

    @Query("DELETE FROM partials WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("SELECT * FROM partials WHERE updatedAt < :before")
    suspend fun olderThan(before: Long): List<PartialTransfer>

    @Query("DELETE FROM partials")
    suspend fun clear()
}
