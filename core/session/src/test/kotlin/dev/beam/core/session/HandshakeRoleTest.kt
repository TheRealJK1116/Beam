package dev.beam.core.session

import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransferError
import dev.beam.core.model.TransportId
import org.junit.Test

/**
 * One state serves the whole app so a transfer can outlive the screen that started it. The
 * cost is that a screen can be shown a beam going the other way, or the leftover result of
 * one — which is exactly what made a send screen open saying "Sent" and close itself.
 */
class HandshakeRoleTest {

    private val peer = DeviceInfo("peer", "Their phone")
    private val token = SessionToken.mint(peer, listOf(TransportId.WIFI_DIRECT))

    @Test
    fun `a finished receive does not belong to the sender`() {
        val completedReceive = HandshakeState.Complete(Role.RECEIVER, peer, 1_000)
        assertThat(completedReceive.belongsTo(Role.SENDER)).isFalse()
        assertThat(completedReceive.belongsTo(Role.RECEIVER)).isTrue()
    }

    @Test
    fun `a receive in progress does not belong to the sender`() {
        val receiving = HandshakeState.Transferring(Role.RECEIVER, peer, 10, 100)
        assertThat(receiving.belongsTo(Role.SENDER)).isFalse()
    }

    @Test
    fun `idle belongs to nobody, so neither screen claims it`() {
        assertThat(HandshakeState.Idle.belongsTo(Role.SENDER)).isFalse()
        assertThat(HandshakeState.Idle.belongsTo(Role.RECEIVER)).isFalse()
    }

    @Test
    fun `every stage of a send belongs to the sender`() {
        listOf(
            HandshakeState.AwaitingTap(Role.SENDER),
            HandshakeState.TokenAcquired(Role.SENDER, token, TransportId.WIFI_DIRECT),
            HandshakeState.Discovering(Role.SENDER, token, TransportId.WIFI_DIRECT),
            HandshakeState.Authenticating(Role.SENDER, token, TransportId.WIFI_DIRECT),
            HandshakeState.Transferring(Role.SENDER, peer, 0, 10),
            HandshakeState.Complete(Role.SENDER, peer, 5),
            HandshakeState.Failed(Role.SENDER, TransferError.Cancelled()),
        ).forEach { state ->
            assertThat(state.belongsTo(Role.SENDER)).isTrue()
            assertThat(state.belongsTo(Role.RECEIVER)).isFalse()
        }
    }

    @Test
    fun `awaiting acceptance is always the receiver's`() {
        val awaiting = HandshakeState.AwaitingAcceptance(
            token = token,
            manifest = dev.beam.core.model.PayloadManifest(dev.beam.core.model.PayloadManifest.Kind.TEXT, text = "x"),
            peer = peer,
        )
        assertThat(awaiting.belongsTo(Role.RECEIVER)).isTrue()
        assertThat(awaiting.belongsTo(Role.SENDER)).isFalse()
    }
}
