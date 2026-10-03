package dev.beam

import android.content.Context
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionToken
import dev.beam.core.transport.Transport
import dev.beam.core.transport.TransportRegistry
import dev.beam.data.history.HistoryDatabase
import dev.beam.data.settings.BeamSettings
import dev.beam.data.settings.SettingsState
import dev.beam.transport.nearby.NearbyTransport
import dev.beam.transport.wifidirect.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manual dependency container.
 *
 * No DI framework: the graph is a dozen objects with one lifetime, and a tap has to be answered
 * from a cold process on a binder thread — fewer moving parts between the OS binding
 * `BeamHceService` and a token coming back is worth more here than annotations.
 */
class BeamContainer(private val context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = BeamSettings(context)
    val database by lazy { HistoryDatabase.create(context) }

    /**
     * A tap is answered on a binder thread in a few milliseconds, which is no place to read a
     * DataStore or query Play Services. Both are mirrored here and kept warm instead.
     */
    private val _settingsSnapshot = MutableStateFlow(SettingsState())
    val settingsSnapshot: StateFlow<SettingsState> get() = _settingsSnapshot

    private val _availableTransports = MutableStateFlow<List<TransportId>>(emptyList())
    val availableTransports: StateFlow<List<TransportId>> get() = _availableTransports

    private val nearby = NearbyTransport(
        context = context,
        localDevice = { localDevice() },
    )

    private val wifiDirect = WifiDirectTransport(context)

    /**
     * Preference order. Wi-Fi Direct is listed ahead of nothing yet — it reports itself
     * unavailable — but it is wired in so that finishing it is a one-line change here.
     */
    val registry: TransportRegistry = TransportRegistry(
        buildList {
            add(nearby)
            add(wifiDirect)
            BeamDebug.extraTransports(context)?.let(::addAll)
        }
    )

    val payloadSource = AndroidPayloadSource(context)
    val payloadSink = AndroidPayloadSink(context)

    val coordinator = TransferCoordinator(
        registry = registry,
        source = payloadSource,
        sink = payloadSink,
        dao = { database.transfers() },
        scope = scope,
    )

    fun localDevice(): DeviceInfo {
        val snapshot = _settingsSnapshot.value
        return DeviceInfo(
            stableId = snapshot.stableId.ifBlank { "pending" },
            displayName = snapshot.deviceName,
        )
    }

    /** Starts mirroring settings and probing transports. Called once from `Application`. */
    fun warmUp() {
        scope.launch {
            settings.ensureStableId()
            settings.state.collect { _settingsSnapshot.value = it }
        }
        scope.launch { refreshTransportAvailability() }
    }

    suspend fun refreshTransportAvailability() {
        _availableTransports.value = registry.available().map { it.id }
    }

    /**
     * Answers a tap. Runs on the HCE binder thread, so it only reads the warm caches above.
     *
     * Returning null declines the tap, which the card side turns into a "conditions not
     * satisfied" status and the sender shows as "that device is not accepting beams".
     */
    fun mintSessionForTap(): SessionToken? {
        val snapshot = _settingsSnapshot.value
        if (!snapshot.receivingEnabled) return null

        val transports = _availableTransports.value.ifEmpty {
            // Nothing probed yet (cold process, first tap after boot). Nearby is the backend we
            // ship enabled, so offer it and let the connection attempt be the real test rather
            // than refusing a tap the user made on purpose.
            listOf(TransportId.NEARBY)
        }

        return SessionToken.mint(
            device = localDevice(),
            transports = transports,
            autoAcceptHint = snapshot.autoAcceptKnownDevices,
        )
    }

    fun transportFor(id: TransportId): Transport? = registry[id]
}
