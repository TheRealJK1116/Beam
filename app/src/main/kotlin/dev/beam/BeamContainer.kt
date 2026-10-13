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

    /** Readable synchronously, because a tap cannot wait for a flow. */
    val identity = DeviceIdentity(context)
    val declinedTaps = DeclinedTaps(context)
    val database by lazy { HistoryDatabase.create(context) }

    /**
     * A tap is answered on a binder thread in a few milliseconds, which is no place to read a
     * DataStore or query Play Services. Both are mirrored here and kept warm instead.
     */
    private val _settingsSnapshot = MutableStateFlow(SettingsState())
    val settingsSnapshot: StateFlow<SettingsState> get() = _settingsSnapshot

    /**
     * Seeded with every registered backend rather than left empty.
     *
     * A tap on a closed phone is answered from a cold process, before the availability probe
     * has had a chance to run - which is precisely when this is read. Starting empty meant
     * that tap fell back to a hardcoded Nearby and silently gave up Wi-Fi Direct, so the
     * beam most likely to need the good path got the one that needs Play Services and
     * produces a Bluetooth pairing dialog.
     *
     * Over-offering is the safe direction: the sender negotiates, and if a backend turns out
     * not to work the transport fallback moves on to the next.
     */
    private val _availableTransports = MutableStateFlow<List<TransportId>>(emptyList())
    val availableTransports: StateFlow<List<TransportId>> get() = _availableTransports

    private val nearby = NearbyTransport(
        context = context,
        localDevice = { localDevice() },
    )

    private val wifiDirect = WifiDirectTransport(context) { localDevice() }

    /**
     * Preference order, and Wi-Fi Direct now leads it.
     *
     * Nearby Connections always raises its control channel over Bluetooth, which makes Android
     * show a pairing dialog and fails outright when Bluetooth cannot connect. Wi-Fi Direct
     * derives its credentials from the tap, so it needs no discovery, no pairing and no Play
     * Services. Nearby stays as the fallback for devices where Wi-Fi Direct is unavailable.
     */
    val registry: TransportRegistry = TransportRegistry(
        buildList {
            add(wifiDirect)
            add(nearby)
            BeamDebug.extraTransports(context)?.let(::addAll)
        }
    )

    /**
     * The backends worth offering before anything has been probed.
     *
     * Not `registry.ids`: in a debug build that also contains the simulator, which must never
     * be advertised to another phone - it is a loopback no other device can reach.
     */
    private val realTransportIds: List<TransportId> = listOf(wifiDirect.id, nearby.id)

    init {
        _availableTransports.value = realTransportIds
    }

    val payloadSource = AndroidPayloadSource(context)
    val payloadSink = AndroidPayloadSink(context) { database.partials() }

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
            stableId = identity.stableId,
            displayName = snapshot.deviceName,
        )
    }

    /**
     * Drops partials nobody came back for.
     *
     * A retained partial is an invisible pending MediaStore item: it does not appear in the
     * gallery, so a user can neither see nor delete it. Without an expiry, every abandoned
     * transfer would quietly consume storage forever.
     */
    private suspend fun expireStalePartials() {
        runCatching {
            val dao = database.partials()
            val cutoff = System.currentTimeMillis() - PARTIAL_LIFETIME_MILLIS
            dao.olderThan(cutoff).forEach { stale ->
                runCatching {
                    context.contentResolver.delete(android.net.Uri.parse(stale.uri), null, null)
                }
                dao.remove(stale.key)
            }
        }
    }

    /** Starts mirroring settings and probing transports. Called once from `Application`. */
    fun warmUp() {
        scope.launch {
            settings.state.collect {
                _settingsSnapshot.value = it
                // Debug builds only; a no-op in release.
                BeamDebug.setSimulationEnabled(it.simulationEnabled)
                refreshTransportAvailability()
            }
        }
        scope.launch { refreshTransportAvailability() }
        scope.launch { expireStalePartials() }
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
        if (!snapshot.receivingEnabled) {
            android.util.Log.i(TAG, "Tap declined: receiving is switched off")
            return null
        }

        // A service woken by a tap cannot show a permission dialog, so refusing here is the
        // only honest option. The tap must not be silently lost, which it was: the notification
        // below needs POST_NOTIFICATIONS, and the user who has granted nothing is exactly the
        // user who ends up here. So it is written down too, and the app owns up when opened.
        if (!BeamPermissions.isReady(context)) {
            declinedTaps.record()
            android.util.Log.w(
                TAG,
                "Tap declined: not ready. missing=${BeamPermissions.missingForTransfer(context)} " +
                    "locationOn=${BeamPermissions.isLocationServiceOn(context)}",
            )
            BeamTransferService.notifySetupRequired(context)
            return null
        }

        // Nothing probed yet means a cold process, not an absence of transports.
        val transports = _availableTransports.value.ifEmpty { realTransportIds }

        return SessionToken.mint(
            device = localDevice(),
            transports = transports,
            autoAcceptHint = snapshot.autoAcceptKnownDevices,
        )
    }

    fun transportFor(id: TransportId): Transport? = registry[id]

    private companion object {
        const val TAG = "BeamContainer"

        /** How long an interrupted transfer stays resumable before its bytes are reclaimed. */
        const val PARTIAL_LIFETIME_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
