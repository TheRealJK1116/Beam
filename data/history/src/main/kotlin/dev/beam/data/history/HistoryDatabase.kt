package dev.beam.data.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.execSQL
import dev.beam.core.model.TransportId
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    @Insert
    suspend fun insert(record: TransferRecord): Long

    @Query("SELECT * FROM transfers ORDER BY completedAt DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<TransferRecord>>

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transfers")
    suspend fun clear()

    /**
     * Backs "auto-accept from known devices": a peer counts as known once a beam from it has
     * completed successfully at least once.
     */
    @Query("SELECT COUNT(*) > 0 FROM transfers WHERE peerStableId = :stableId AND failureReason IS NULL")
    suspend fun isKnownPeer(stableId: String): Boolean

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun byId(id: Long): TransferRecord?
}

internal class Converters {
    @TypeConverter
    fun toTransport(value: String): TransportId =
        runCatching { TransportId.valueOf(value) }.getOrDefault(TransportId.NEARBY)

    @TypeConverter
    fun fromTransport(id: TransportId): String = id.name
}

@Database(
    entities = [TransferRecord::class, PartialTransfer::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HistoryDatabase : RoomDatabase() {

    abstract fun transfers(): TransferDao

    abstract fun partials(): PartialDao

    companion object {
        /**
         * Adds the partials table. Written out rather than destructive: the history is the
         * user's own record of what they sent, and losing it to a schema change would be a
         * poor trade for a table they never asked for.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `partials` (
                        `key` TEXT NOT NULL,
                        `uri` TEXT NOT NULL,
                        `bytesWritten` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `peerStableId` TEXT NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_partials_updatedAt` ON `partials` (`updatedAt`)")
            }
        }

        fun create(context: Context): HistoryDatabase =
            Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "beam-history.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Used by tests and by the Dev screen's throwaway simulations. */
        fun createInMemory(context: Context): HistoryDatabase =
            Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
