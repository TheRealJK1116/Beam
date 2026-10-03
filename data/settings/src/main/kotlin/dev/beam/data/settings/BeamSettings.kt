package dev.beam.data.settings

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * User-visible settings, plus the one piece of durable identity Beam keeps.
 *
 * [SettingsState.stableId] is a random per-install id, not a hardware id: it exists only so
 * "auto-accept from devices I've accepted before" can recognise a phone across taps. It never
 * leaves the device except inside a session token handed over NFC.
 */
data class SettingsState(
    val receivingEnabled: Boolean = true,
    val autoAcceptKnownDevices: Boolean = false,
    val deviceName: String = Build.MODEL ?: "Android phone",
    val stableId: String = "",
    /** Debug builds only; the Dev screen routes beams through FakeTransport when true. */
    val simulationEnabled: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "beam_settings")

class BeamSettings(context: Context) {

    private val store = context.applicationContext.dataStore

    val state: Flow<SettingsState> = store.data.map { prefs ->
        SettingsState(
            receivingEnabled = prefs[Keys.receiving] ?: true,
            autoAcceptKnownDevices = prefs[Keys.autoAccept] ?: false,
            deviceName = prefs[Keys.deviceName]?.takeIf { it.isNotBlank() } ?: (Build.MODEL ?: "Android phone"),
            stableId = prefs[Keys.stableId].orEmpty(),
            simulationEnabled = prefs[Keys.simulation] ?: false,
        )
    }

    suspend fun setReceivingEnabled(enabled: Boolean) = edit { it[Keys.receiving] = enabled }

    suspend fun setAutoAcceptKnownDevices(enabled: Boolean) = edit { it[Keys.autoAccept] = enabled }

    suspend fun setSimulationEnabled(enabled: Boolean) = edit { it[Keys.simulation] = enabled }

    suspend fun setDeviceName(name: String) = edit {
        // The name travels in a size-capped NFC token; keep it short at the source.
        it[Keys.deviceName] = name.trim().take(32)
    }

    /** Creates the install id on first use and returns the same value forever after. */
    suspend fun ensureStableId(): String {
        var id = ""
        store.edit { prefs ->
            id = prefs[Keys.stableId].orEmpty().ifBlank {
                // 22 chars, inside the token's 24-byte cap for this field.
                UUID.randomUUID().toString().replace("-", "").take(22)
            }
            prefs[Keys.stableId] = id
        }
        return id
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(block)
    }

    private object Keys {
        val receiving = booleanPreferencesKey("receiving_enabled")
        val autoAccept = booleanPreferencesKey("auto_accept_known")
        val deviceName = stringPreferencesKey("device_name")
        val stableId = stringPreferencesKey("stable_id")
        val simulation = booleanPreferencesKey("simulation_enabled")
    }
}
