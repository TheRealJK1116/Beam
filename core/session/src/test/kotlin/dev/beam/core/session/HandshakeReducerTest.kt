package dev.beam.core.session

import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransferError
import dev.beam.core.model.PayloadManifest
import dev.beam.core.model.TransportId
import org.junit.Test

class HandshakeReducerTest {

    private val token = SessionToken.mint(DeviceInfo("peer", "Pixel"), listOf(TransportId.NEARBY))
    private val peer = DeviceInfo("peer", "Pixel")

    private fun run(vararg events: HandshakeEvent): HandshakeState =
        events.fold<HandshakeEvent, HandshakeState>(HandshakeState.Idle, HandshakeReducer::reduce)

    @Test
    fun `the happy sender path reaches Complete`() {
        val state = run(
            HandshakeEvent.Begin(Role.SENDER),
            HandshakeEvent.TapCompleted(token, TransportId.NEARBY),
            HandshakeEvent.DiscoveryStarted,
            HandshakeEvent.EndpointFound,
            HandshakeEvent.Authenticated(peer),
            HandshakeEvent.TransferStarted(peer, totalBytes = 1_000),
            HandshakeEvent.Progressed(500),
            HandshakeEvent.Progressed(1_000),
            HandshakeEvent.Finished(durationMillis = 2_400),
        )
        assertThat(state).isEqualTo(HandshakeState.Complete(Role.SENDER, peer, 2_400))
    }

    @Test
    fun `the receiver path pauses for the user then transfers`() {
        val manifest = PayloadManifest(PayloadManifest.Kind.TEXT, text = "hello")
        val awaiting = run(
            HandshakeEvent.Begin(Role.RECEIVER),
            HandshakeEvent.TapCompleted(token, TransportId.NEARBY),
            HandshakeEvent.AdvertisingStarted,
            HandshakeEvent.EndpointFound,
            HandshakeEvent.ManifestReceived(manifest, peer),
        )
        assertThat(awaiting).isInstanceOf(HandshakeState.AwaitingAcceptance::class.java)

        val transferring = HandshakeReducer.reduce(awaiting, HandshakeEvent.Accepted)
        assertThat(transferring).isInstanceOf(HandshakeState.Transferring::class.java)
        assertThat((transferring as HandshakeState.Transferring).totalBytes).isEqualTo(manifest.totalBytes)
        assertThat(transferring.role).isEqualTo(Role.RECEIVER)
    }

    @Test
    fun `declining fails the beam as rejected`() {
        val awaiting = run(
            HandshakeEvent.Begin(Role.RECEIVER),
            HandshakeEvent.TapCompleted(token, TransportId.NEARBY),
            HandshakeEvent.EndpointFound,
            HandshakeEvent.ManifestReceived(PayloadManifest(PayloadManifest.Kind.TEXT, text = "hi"), peer),
        )
        val state = HandshakeReducer.reduce(awaiting, HandshakeEvent.Declined)
        assertThat((state as HandshakeState.Failed).error).isInstanceOf(TransferError.Rejected::class.java)
    }

    @Test
    fun `a discovery timeout is reported as not finding the device`() {
        val state = run(
            HandshakeEvent.Begin(Role.SENDER),
            HandshakeEvent.TapCompleted(token, TransportId.NEARBY),
            HandshakeEvent.DiscoveryStarted,
            HandshakeEvent.Timeout,
        )
        assertThat((state as HandshakeState.Failed).error).isInstanceOf(TransferError.DiscoveryTimeout::class.java)
    }

    @Test
    fun `an authentication timeout is reported distinctly from discovery`() {
        val state = run(
            HandshakeEvent.Begin(Role.SENDER),
            HandshakeEvent.TapCompleted(token, TransportId.NEARBY),
            HandshakeEvent.EndpointFound,
            HandshakeEvent.Timeout,
        )
        assertThat((state as HandshakeState.Failed).error)
            .isInstanceOf(TransferError.AuthenticationFailed::class.java)
    }

    @Test
    fun `terminal states absorb late events`() {
        val complete = run(
            HandshakeEvent.Begin(Role.SENDER),
            HandshakeEvent.TapCompleted(token, TransportId.NEARBY),
            HandshakeEvent.EndpointFound,
            HandshakeEvent.Authenticated(peer),
            HandshakeEvent.TransferStarted(peer, 10),
            HandshakeEvent.Finished(100),
        )
        // Transports routinely emit a trailing progress callback after completion.
        val after = HandshakeReducer.reduce(complete, HandshakeEvent.Progressed(5))
        assertThat(after).isEqualTo(complete)
        assertThat(HandshakeReducer.reduce(complete, HandshakeEvent.Timeout)).isEqualTo(complete)
    }

    @Test
    fun `Begin restarts from a terminal state`() {
        val failed = HandshakeState.Failed(Role.SENDER, TransferError.Cancelled())
        assertThat(HandshakeReducer.reduce(failed, HandshakeEvent.Begin(Role.SENDER)))
            .isEqualTo(HandshakeState.AwaitingTap(Role.SENDER))
    }

    @Test
    fun `progress never walks backwards`() {
        val transferring = HandshakeState.Transferring(Role.RECEIVER, peer, transferredBytes = 800, totalBytes = 1_000)
        val state = HandshakeReducer.reduce(transferring, HandshakeEvent.Progressed(300))
        assertThat((state as HandshakeState.Transferring).transferredBytes).isEqualTo(800)
    }

    @Test
    fun `progress beyond the declared total does not produce a fraction above one`() {
        val transferring = HandshakeState.Transferring(Role.SENDER, peer, 0, totalBytes = 100)
        val state = HandshakeReducer.reduce(transferring, HandshakeEvent.Progressed(500))
        assertThat((state as HandshakeState.Transferring).fraction).isEqualTo(1f)
    }

    @Test
    fun `cancelling at any stage fails the beam`() {
        val stages = listOf(
            HandshakeState.AwaitingTap(Role.SENDER),
            HandshakeState.TokenAcquired(Role.SENDER, token, TransportId.NEARBY),
            HandshakeState.Discovering(Role.SENDER, token, TransportId.NEARBY),
            HandshakeState.Transferring(Role.SENDER, peer, 1, 2),
        )
        stages.forEach { stage ->
            val state = HandshakeReducer.reduce(stage, HandshakeEvent.Cancelled)
            assertThat((state as HandshakeState.Failed).error).isInstanceOf(TransferError.Cancelled::class.java)
        }
    }

    @Test
    fun `every non-terminal stage that can stall has a timeout budget`() {
        val stalling = listOf(
            HandshakeState.AwaitingTap(Role.SENDER),
            HandshakeState.TokenAcquired(Role.SENDER, token, TransportId.NEARBY),
            HandshakeState.Discovering(Role.SENDER, token, TransportId.NEARBY),
            HandshakeState.Authenticating(Role.SENDER, token, TransportId.NEARBY),
        )
        stalling.forEach { assertThat(HandshakeTimeouts.forState(it)).isNotNull() }
        // An in-flight transfer must not be killed by a wall clock.
        assertThat(HandshakeTimeouts.forState(HandshakeState.Transferring(Role.SENDER, peer, 0, 0))).isNull()
    }

    @Test
    fun `a tap is ignored once a transfer is already running`() {
        val transferring = HandshakeState.Transferring(Role.RECEIVER, peer, 10, 100)
        val other = SessionToken.mint(DeviceInfo("other", "Other"), listOf(TransportId.NEARBY))
        assertThat(HandshakeReducer.reduce(transferring, HandshakeEvent.TapCompleted(other, TransportId.NEARBY)))
            .isEqualTo(transferring)
    }
}
