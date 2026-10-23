package dev.beam

import android.content.Context
import dev.beam.core.model.BeamPayload
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransferEvent
import dev.beam.core.model.PayloadManifest
import dev.beam.core.model.TransportId
import dev.beam.data.history.TransferRecord
import dev.beam.core.session.SessionToken
import dev.beam.core.transport.Transport
import dev.beam.transport.fake.FakeEther
import dev.beam.transport.fake.FakeStage
import dev.beam.transport.fake.FakeTransport
import dev.beam.transport.fake.FakeTransportConfig
import dev.beam.transport.fake.MemoryPayloadSink
import dev.beam.transport.fake.MemoryPayloadSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * The developer simulation tools, present in debug builds only.
 *
 * A beam needs two phones, an NFC tap and a radio link, which makes the interesting paths —
 * a declined transfer, a forged proof of tap, a connection dropping at 60% — nearly impossible
 * to reproduce on demand. These scenarios run the real session protocol over
 * [FakeTransport] with both ends in this process, so the whole flow can be exercised and
 * watched on a single device, or none.
 *
 * The release variant of this file is a stub that depends on nothing, so none of this exists in
 * a shipping APK.
 */
object BeamDebug {

    const val IS_SIMULATION_AVAILABLE = true

    /** Shared so both ends of a loopback beam find each other. */
    val ether = FakeEther()

    /**
     * Unavailable unless the Dev screen's simulation switch is on.
     *
     * An available transport is offered to the other phone inside the session token and joins
     * the fallback chain, so a simulator left switched on would advertise a loopback that no
     * other device can ever reach, and would be "fallen back" to after the real radios failed.
     */
    val fakeTransport = FakeTransport(ether, FakeTransportConfig(available = false))

    /** Driven by the simulation setting, which only exists in debug builds. */
    fun setSimulationEnabled(enabled: Boolean) {
        fakeTransport.configure { it.copy(available = enabled) }
    }

    fun extraTransports(context: Context): List<Transport> = listOf(fakeTransport)

    /**
     * Cuts the next real send at [bytes], so resume can be tested against an interruption at
     * a known offset rather than by pulling two phones apart and hoping.
     */
    fun armSendInterruption(bytes: Long) = dev.beam.core.transport.BeamFaults.armSendInterruption(bytes)

    fun disarmInterruption() = dev.beam.core.transport.BeamFaults.disarm()

    val isInterruptionArmed: Boolean get() = dev.beam.core.transport.BeamFaults.isArmed

    /** A photo-sized payload, so timings mean something. */
    private const val PHOTO_BYTES = 4 * 1024 * 1024

    data class Scenario(
        val id: String,
        val title: String,
        val description: String,
        val config: FakeTransportConfig,
        val payload: () -> BeamPayload,
    )

    private val photo: () -> BeamPayload = {
        BeamPayload.Files(
            listOf(
                dev.beam.core.model.FileRef(
                    uri = "fake://photo.jpg",
                    name = "simulated_photo.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = PHOTO_BYTES.toLong(),
                )
            )
        )
    }

    val scenarios: List<Scenario> = listOf(
        Scenario(
            id = "happy",
            title = "Photo, good link",
            description = "4 MB over a healthy connection — the 5-second target",
            config = FakeTransportConfig(throughputBytesPerSecond = 20_000_000),
            payload = photo,
        ),
        Scenario(
            id = "slow",
            title = "Photo, poor link",
            description = "Same photo at 1 MB/s, to see progress actually crawl",
            config = FakeTransportConfig(
                throughputBytesPerSecond = 1_000_000,
                discoveryDelay = 1_500.milliseconds,
            ),
            payload = photo,
        ),
        Scenario(
            id = "text",
            title = "Text beam",
            description = "The no-file path: manifest, accept, done",
            config = FakeTransportConfig(),
            payload = { BeamPayload.Text("Beamed from the simulator") },
        ),
        Scenario(
            id = "decline",
            title = "Receiver declines",
            description = "Both sides should end cleanly with nothing written",
            config = FakeTransportConfig(),
            payload = photo,
        ),
        Scenario(
            id = "forged",
            title = "Forged proof of tap",
            description = "A device that saw the advertisement but never tapped",
            config = FakeTransportConfig(forgeProof = true),
            payload = photo,
        ),
        Scenario(
            id = "drop",
            title = "Link drops mid-transfer",
            description = "Fails at ~50%; the partial file must be discarded",
            config = FakeTransportConfig(failAt = FakeStage.TRANSFER),
            payload = photo,
        ),
        Scenario(
            id = "nobody",
            title = "Nobody answers",
            description = "Tap, then the other phone walks away",
            config = FakeTransportConfig(
                failAt = FakeStage.DISCOVERY,
                discoveryTimeout = 2_000.milliseconds,
            ),
            payload = photo,
        ),
    )

    /**
     * Runs one scenario end to end and reports what both sides saw. Returns the elapsed
     * milliseconds so the Dev screen can show it against the 5-second goal.
     */
    suspend fun runScenario(
        scenario: Scenario,
        log: (String) -> Unit,
    ): Long = coroutineScope {
        val localEther = FakeEther()
        val token = SessionToken.mint(
            device = DeviceInfo("sim-receiver", "Simulated receiver"),
            transports = listOf(TransportId.FAKE),
        )

        val source = MemoryPayloadSource()
        val payload = scenario.payload()
        if (payload is BeamPayload.Files) {
            payload.files.forEach { source.put(it.uri, MemoryPayloadSource.syntheticBytes(it.sizeBytes.toInt())) }
        }
        val sink = MemoryPayloadSink()

        val receiver = FakeTransport(localEther, scenario.config)
        val sender = FakeTransport(localEther, scenario.config)
        val accept = scenario.id != "decline"

        val startedAt = System.currentTimeMillis()
        log("— ${scenario.title} —")

        val receiving = launch {
            receiver.receive(token, sink) { manifest, peer ->
                log("recv: offered ${manifest.describe()} from ${peer.displayName}")
                accept
            }.collect { log("recv: ${it.describe()}") }
        }

        val sending = launch {
            sender.send(token, payload, source).collect { log("send: ${it.describe()}") }
        }

        receiving.join()
        sending.join()

        val elapsed = System.currentTimeMillis() - startedAt
        log("finished in ${elapsed} ms; ${sink.received.size} item(s) written, ${sink.discardedNames.size} discarded")
        elapsed
    }

    /**
     * Pretends a phone was just tapped against this one: mints a real session through the real
     * container and starts the real receive flow, so the notification, the confirmation screen
     * and the foreground service can all be exercised without a second device.
     */
    fun simulateIncomingTap(context: Context, container: BeamContainer): Boolean {
        val token = container.mintSessionForTap() ?: return false
        BeamTransferService.startReceiving(context, token)
        return true
    }

    /**
     * Writes a real .apk into history as though it had just been received.
     *
     * Opening a received file can only be tested on a file that was genuinely received, and a
     * beam needs two phones. This takes the same route a real one does — the real MediaStore
     * sink, the real history table — so what lands is indistinguishable from the real thing
     * and the Open button exercises the actual code path.
     *
     * It copies this very app's installed APK, so there is always something valid to hand to
     * an installer rather than a file that merely ends in .apk.
     */
    suspend fun fabricateReceivedApk(context: Context, container: BeamContainer): String {
        val ourApk = java.io.File(context.applicationInfo.sourceDir)
        val entry = PayloadManifest.Entry(
            name = "beam-sample.apk",
            mimeType = "application/vnd.android.package-archive",
            sizeBytes = ourApk.length(),
        )

        val destination = container.payloadSink.create(entry, peerStableId = "debug-fabricated")
        destination.stream.use { out -> ourApk.inputStream().use { it.copyTo(out) } }
        val uri = destination.commit()

        container.database.transfers().insert(
            TransferRecord(
                outgoing = false,
                label = "beam-sample.apk",
                kind = "FILES",
                sizeBytes = entry.sizeBytes,
                peerStableId = "debug-fabricated",
                peerName = "Fabricated",
                transport = TransportId.FAKE,
                completedAt = System.currentTimeMillis(),
                durationMillis = 0,
                primaryUri = uri,
            )
        )

        // What MediaStore decided it is, which is what the Open button will act on. If this
        // does not say package-archive, the receive side mangled the type on the way in.
        val storedType = context.contentResolver.getType(android.net.Uri.parse(uri))
        return "wrote $uri (${entry.sizeBytes} B), MediaStore calls it $storedType"
    }
}

private fun TransferEvent.describe(): String = when (this) {
    TransferEvent.Discovering -> "discovering"
    TransferEvent.Connecting -> "connecting"
    is TransferEvent.ManifestOffered -> "manifest offered (${manifest.totalBytes} B)"
    is TransferEvent.FileStarted -> "file ${index + 1} of $count: $name"
    is TransferEvent.Started -> "started, ${totalBytes} B from ${peer.displayName}"
    is TransferEvent.Progress -> "progress ${(fraction * 100).toInt()}%"
    is TransferEvent.Completed -> "completed in ${durationMillis} ms"
    is TransferEvent.Failed -> "FAILED: ${error.message}"
}
