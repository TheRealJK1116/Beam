package dev.beam.transport.fake

import dev.beam.core.model.BeamPayload
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.PayloadManifest
import dev.beam.core.model.TransferError
import dev.beam.core.model.TransferEvent
import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionAuth
import dev.beam.core.session.SessionToken
import dev.beam.core.transport.PayloadSink
import dev.beam.core.transport.PayloadSource
import dev.beam.core.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A complete [Transport] that never touches a radio.
 *
 * Both ends run in one process and exchange real bytes through real [PayloadSource] /
 * [PayloadSink] plumbing, so a loopback beam exercises the manifest, the proof-of-tap, the
 * accept prompt, progress reporting and the sink's commit/discard — everything except the
 * radio itself. That makes it the engine behind the developer simulation tools and the one
 * transport that tests can drive deterministically.
 *
 * Instances sharing an [ether] can see each other. [config] decides how slow, how flaky, and
 * how hostile this fake is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeTransport(
    private val ether: FakeEther = FakeEther.Shared,
    config: FakeTransportConfig = FakeTransportConfig(),
) : Transport {

    override val id: TransportId = TransportId.FAKE

    private val _config = MutableStateFlow(config)

    /** Live so the Dev screen can retune a running simulation. */
    val config: StateFlow<FakeTransportConfig> get() = _config

    fun configure(block: (FakeTransportConfig) -> FakeTransportConfig) {
        _config.value = block(_config.value)
    }

    override suspend fun isAvailable(): Boolean = _config.value.available

    override fun receive(
        token: SessionToken,
        sink: PayloadSink,
        decide: suspend (PayloadManifest, DeviceInfo) -> Boolean,
    ): Flow<TransferEvent> = flow {
        val cfg = _config.value
        emit(TransferEvent.Discovering)

        val advert = FakeEther.Advert(token)
        ether.publish(advert)
        try {
            cfg.advertiseDelay.sleep()
            if (cfg.failAt == FakeStage.DISCOVERY) {
                emit(TransferEvent.Failed(TransferError.DiscoveryTimeout("Simulated: no sender appeared")))
                return@flow
            }

            // Wait for a sender to walk up to this advertisement.
            val link = select<FakeEther.Link?> {
                advert.incoming.onReceive { it }
                onTimeout(cfg.discoveryTimeout) { null }
            } ?: run {
                emit(TransferEvent.Failed(TransferError.DiscoveryTimeout("Simulated: no sender appeared")))
                return@flow
            }

            emit(TransferEvent.Connecting)
            cfg.connectDelay.sleep()

            val proofOk = !cfg.requireValidProof ||
                SessionAuth.verify(token, link.endpointId, link.proof)
            if (!proofOk || cfg.failAt == FakeStage.AUTH) {
                val error = TransferError.AuthenticationFailed(
                    if (proofOk) "Simulated authentication failure" else "Proof of tap did not verify"
                )
                link.finish(Result.failure(FakeFailure(error)))
                emit(TransferEvent.Failed(error))
                return@flow
            }

            if (cfg.failAt == FakeStage.MANIFEST) {
                val error = TransferError.Io("Simulated: manifest never arrived")
                link.finish(Result.failure(FakeFailure(error)))
                emit(TransferEvent.Failed(error))
                return@flow
            }

            emit(TransferEvent.ManifestOffered(link.manifest, link.peer))

            val accepted = try {
                withTimeout(cfg.acceptTimeout) { decide(link.manifest, link.peer) }
            } catch (_: TimeoutCancellationException) {
                false
            }
            link.accepted.complete(accepted)
            if (!accepted) {
                val error = TransferError.Rejected()
                link.finish(Result.failure(FakeFailure(error)))
                emit(TransferEvent.Failed(error))
                return@flow
            }

            val total = link.manifest.totalBytes
            emit(TransferEvent.Started(total, link.peer))

            val started = ether.clock()
            val result = runCatching { copy(link, sink, cfg, total) { emit(it) } }
            val duration = ether.clock() - started

            result.fold(
                onSuccess = { uris ->
                    link.finish(Result.success(duration))
                    emit(TransferEvent.Completed(duration, uris))
                },
                onFailure = { cause ->
                    val error = cause.asTransferError("Simulated transfer failure")
                    link.finish(Result.failure(FakeFailure(error)))
                    emit(TransferEvent.Failed(error))
                },
            )
        } finally {
            ether.withdraw(advert)
        }
    }

    override fun send(
        token: SessionToken,
        payload: BeamPayload,
        source: PayloadSource,
    ): Flow<TransferEvent> = flow {
        val cfg = _config.value
        emit(TransferEvent.Discovering)
        cfg.discoveryDelay.sleep()

        if (cfg.failAt == FakeStage.DISCOVERY) {
            emit(TransferEvent.Failed(TransferError.DiscoveryTimeout("Simulated: receiver not found")))
            return@flow
        }

        val advert = ether.discover(token.advertisingName, cfg.discoveryTimeout)
        if (advert == null) {
            emit(TransferEvent.Failed(TransferError.DiscoveryTimeout("Simulated: receiver not found")))
            return@flow
        }

        emit(TransferEvent.Connecting)
        cfg.connectDelay.sleep()

        val endpointId = ether.nextEndpointId()
        val link = FakeEther.Link(
            endpointId = endpointId,
            // A hostile sender in the simulation signs with the wrong secret.
            proof = if (cfg.forgeProof) {
                SessionAuth.proofFor(token.copy(secret = ByteArray(SessionToken.SECRET_BYTES)), endpointId)
            } else {
                SessionAuth.proofFor(token, endpointId)
            },
            peer = cfg.localDevice,
            manifest = PayloadManifest.of(payload),
            payload = payload,
            source = source,
        )
        advert.incoming.send(link)

        val accepted = runCatching { withTimeout(cfg.acceptTimeout) { link.accepted.await() } }
            .getOrDefault(false)
        if (!accepted) {
            emit(TransferEvent.Failed(link.outcome.takeIfFailed() ?: TransferError.Rejected()))
            return@flow
        }

        val total = link.manifest.totalBytes
        emit(TransferEvent.Started(total, advert.token.device))

        // Mirror the receiver's progress: in a real transport this would be the sender's own
        // byte-sent callback; here the receiver is the side doing the copying. Polled rather
        // than collected so that the loop ends the moment the outcome lands, even if no
        // further progress is published.
        var lastReported = -1L
        while (!link.outcome.isCompleted) {
            val sent = link.progress.value
            if (sent != lastReported) {
                emit(TransferEvent.Progress(sent, total))
                lastReported = sent
            }
            select {
                link.outcome.onAwait { }
                onTimeout(PROGRESS_POLL) { }
            }
        }

        link.outcome.await().fold(
            onSuccess = {
                if (lastReported != total) emit(TransferEvent.Progress(total, total))
                emit(TransferEvent.Completed(it))
            },
            onFailure = { emit(TransferEvent.Failed(it.asTransferError("Simulated failure"))) },
        )
    }

    /** Streams the payload through the sink, pacing it to the configured throughput. */
    private suspend fun copy(
        link: FakeEther.Link,
        sink: PayloadSink,
        cfg: FakeTransportConfig,
        total: Long,
        emit: suspend (TransferEvent) -> Unit,
    ): List<String> {
        var transferred = 0L

        suspend fun advance(bytes: Int) {
            transferred += bytes
            cfg.pacingFor(bytes.toLong()).sleep()
            if (cfg.failAt == FakeStage.TRANSFER && transferred * 2 >= total) {
                throw FakeFailure(TransferError.Io("Simulated: connection dropped mid-transfer"))
            }
            link.progress.value = transferred
            emit(TransferEvent.Progress(transferred, total))
        }

        return when (val payload = link.payload) {
            is BeamPayload.Text, is BeamPayload.Url -> {
                val text = (payload as? BeamPayload.Text)?.text ?: (payload as BeamPayload.Url).url
                advance(text.toByteArray(Charsets.UTF_8).size)
                listOf(sink.acceptText(link.manifest, text))
            }

            is BeamPayload.Files -> buildList {
                for ((index, entry) in link.manifest.files.withIndex()) {
                    val destination = sink.create(entry)
                    val ok = runCatching {
                        link.source.open(payload.files[index]).use { input ->
                            destination.stream.use { output ->
                                val buffer = ByteArray(cfg.chunkBytes)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    advance(read)
                                }
                            }
                        }
                    }
                    if (ok.isFailure) {
                        destination.discard()
                        throw ok.exceptionOrNull()!!
                    }
                    add(destination.commit())
                }
            }
        }
    }

    private suspend fun Duration.sleep() {
        if (this > Duration.ZERO) delay(this)
    }

    private companion object {
        val PROGRESS_POLL = 40.milliseconds
    }
}

/** Which stage of a simulated beam should blow up. */
enum class FakeStage { DISCOVERY, AUTH, MANIFEST, TRANSFER }

/**
 * Knobs for the simulation. The defaults model a good day on real hardware: sub-second
 * discovery and roughly 20 MB/s, which is what the 5-second photo target assumes.
 */
data class FakeTransportConfig(
    val available: Boolean = true,
    val localDevice: DeviceInfo = DeviceInfo("fake-sender", "Simulated phone"),
    val advertiseDelay: Duration = 50.milliseconds,
    val discoveryDelay: Duration = 400.milliseconds,
    val connectDelay: Duration = 350.milliseconds,
    val discoveryTimeout: Duration = 10.seconds,
    val acceptTimeout: Duration = 30.seconds,
    val throughputBytesPerSecond: Long = 20_000_000,
    val chunkBytes: Int = 64 * 1024,
    /** Stage at which to inject a failure, or null for a clean run. */
    val failAt: FakeStage? = null,
    /** When true the sender signs with the wrong secret, exercising the auth rejection path. */
    val forgeProof: Boolean = false,
    /** Lets a test bypass proof checking to isolate other behaviour. */
    val requireValidProof: Boolean = true,
) {
    init {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
        require(throughputBytesPerSecond > 0) { "throughput must be positive" }
    }

    /** How long [bytes] should take at the configured throughput. */
    fun pacingFor(bytes: Long): Duration =
        (bytes * 1000.0 / throughputBytesPerSecond).toLong().milliseconds
}

/** A transport-level failure carrying the error the UI should show. */
class FakeFailure(val error: TransferError) : Exception(error.message)

private fun Throwable.asTransferError(fallback: String): TransferError = when (this) {
    is FakeFailure -> error
    else -> TransferError.Io(message ?: fallback)
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun CompletableDeferred<Result<Long>>.takeIfFailed(): TransferError? =
    if (isCompleted) getCompleted().exceptionOrNull()?.let { (it as? FakeFailure)?.error } else null

/**
 * The in-process stand-in for the air between two phones. Advertisements are published here and
 * senders look them up by name, exactly as they would scan for an endpoint.
 */
class FakeEther(val clock: () -> Long = System::currentTimeMillis) {

    private val adverts = ConcurrentHashMap<String, Advert>()
    private var endpointCounter = 0

    val advertisedNames: Set<String> get() = adverts.keys.toSet()

    fun publish(advert: Advert) {
        adverts[advert.token.advertisingName] = advert
    }

    fun withdraw(advert: Advert) {
        adverts.remove(advert.token.advertisingName, advert)
    }

    /** Polls like a real scanner would, until [timeout]. */
    suspend fun discover(name: String, timeout: Duration): Advert? {
        val deadline = clock() + timeout.inWholeMilliseconds
        while (clock() <= deadline) {
            adverts[name]?.let { return it }
            delay(25)
        }
        return adverts[name]
    }

    @Synchronized
    fun nextEndpointId(): String = "FAKE%04d".format(++endpointCounter)

    class Advert(val token: SessionToken) {
        val incoming = Channel<Link>(Channel.BUFFERED)
    }

    class Link(
        val endpointId: String,
        val proof: ByteArray,
        val peer: DeviceInfo,
        val manifest: PayloadManifest,
        val payload: BeamPayload,
        val source: PayloadSource,
    ) {
        val accepted = CompletableDeferred<Boolean>()
        val progress = MutableStateFlow(0L)
        val outcome = CompletableDeferred<Result<Long>>()

        fun finish(result: Result<Long>) {
            accepted.complete(result.isSuccess)
            outcome.complete(result)
        }
    }

    companion object {
        /** Default instance, so a FakeTransport on each side of a loopback beam connects. */
        val Shared = FakeEther()
    }
}
