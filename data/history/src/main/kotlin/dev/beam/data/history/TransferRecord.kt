package dev.beam.data.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.beam.core.model.TransportId

/**
 * One finished (or failed) beam, for the history list and for "auto-accept from devices I have
 * accepted before".
 *
 * Deliberately thin: a label, a size, who, when, which way. Beam has no analytics, so nothing
 * here leaves the device and nothing records payload contents.
 */
@Entity(
    tableName = "transfers",
    indices = [Index("completedAt"), Index("peerStableId")],
)
data class TransferRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** True when this device sent it. */
    val outgoing: Boolean,

    val label: String,
    val kind: String,
    val sizeBytes: Long,

    val peerStableId: String,
    val peerName: String,

    @ColumnInfo(defaultValue = "NEARBY")
    val transport: TransportId,

    val completedAt: Long,
    val durationMillis: Long,

    /** Null when the beam succeeded; a short reason when it did not. */
    val failureReason: String? = null,

    /** Content URI of the single received item, when there is one to reopen. */
    val primaryUri: String? = null,
) {
    val succeeded: Boolean get() = failureReason == null
}
