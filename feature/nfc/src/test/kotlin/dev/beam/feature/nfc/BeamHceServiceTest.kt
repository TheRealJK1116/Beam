package dev.beam.feature.nfc

import com.google.common.truth.Truth.assertThat
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionToken
import dev.beam.core.session.SessionTokenCodec
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Drives the card side of the tap. These are the only assertions that can be made about the NFC
 * exchange without two phones, so they cover the whole command surface including what a
 * misbehaving reader gets back.
 */
@RunWith(RobolectricTestRunner::class)
class BeamHceServiceTest {

    private lateinit var service: BeamHceService
    private var publishCount = 0

    private val token = SessionToken.mint(
        device = DeviceInfo("receiver-id", "Pixel 9"),
        transports = listOf(TransportId.NEARBY),
    )

    @Before
    fun setUp() {
        publishCount = 0
        service = Robolectric.setupService(BeamHceService::class.java)
        BeamNfc.sessionPublisher = SessionPublisher {
            publishCount++
            token
        }
    }

    @After
    fun tearDown() {
        BeamNfc.sessionPublisher = null
    }

    private fun send(apdu: ByteArray): ByteArray = service.processCommandApdu(apdu, null)

    @Test
    fun `a full exchange returns the session token`() {
        assertThat(send(BeamApdu.selectCommand())).isEqualTo(BeamApdu.SW_OK)

        val response = send(BeamApdu.getSessionCommand())
        val body = BeamApdu.responseBody(response)
        assertThat(body).isNotNull()

        val decoded = SessionTokenCodec.decode(body!!).getOrThrow()
        assertThat(decoded).isEqualTo(token)
        assertThat(publishCount).isEqualTo(1)
    }

    @Test
    fun `the response fits what a reader can take in one short APDU`() {
        send(BeamApdu.selectCommand())
        assertThat(send(BeamApdu.getSessionCommand()).size).isAtMost(255)
    }

    @Test
    fun `asking for a session without selecting first is refused`() {
        assertThat(send(BeamApdu.getSessionCommand())).isEqualTo(BeamApdu.SW_CONDITIONS_NOT_SATISFIED)
        assertThat(publishCount).isEqualTo(0)
    }

    @Test
    fun `no session is published when receiving is switched off`() {
        BeamNfc.sessionPublisher = SessionPublisher { null }
        send(BeamApdu.selectCommand())
        assertThat(send(BeamApdu.getSessionCommand())).isEqualTo(BeamApdu.SW_CONDITIONS_NOT_SATISFIED)
    }

    @Test
    fun `a tap before the app has registered anything is refused rather than crashing`() {
        BeamNfc.sessionPublisher = null
        send(BeamApdu.selectCommand())
        assertThat(send(BeamApdu.getSessionCommand())).isEqualTo(BeamApdu.SW_CONDITIONS_NOT_SATISFIED)
    }

    @Test
    fun `a publisher that throws does not take the service down`() {
        BeamNfc.sessionPublisher = SessionPublisher { error("transport exploded") }
        send(BeamApdu.selectCommand())
        assertThat(send(BeamApdu.getSessionCommand())).isEqualTo(BeamApdu.SW_CONDITIONS_NOT_SATISFIED)
    }

    @Test
    fun `selecting a different application is answered with file not found`() {
        val otherAid = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x05, 0xA0.toByte(), 0x00, 0x00, 0x00, 0x03)
        assertThat(send(otherAid)).isEqualTo(BeamApdu.SW_FILE_NOT_FOUND)
    }

    @Test
    fun `an unknown instruction is rejected`() {
        send(BeamApdu.selectCommand())
        assertThat(send(byteArrayOf(0x80.toByte(), 0x99.toByte(), 0x00, 0x00))).isEqualTo(BeamApdu.SW_INS_NOT_SUPPORTED)
    }

    @Test
    fun `a null or runt apdu is answered rather than ignored`() {
        assertThat(service.processCommandApdu(null, null)).isEqualTo(BeamApdu.SW_UNKNOWN)
        assertThat(send(byteArrayOf(0x00))).isEqualTo(BeamApdu.SW_INS_NOT_SUPPORTED)
    }

    @Test
    fun `each tap mints a fresh single-use session`() {
        val tokens = mutableListOf<SessionToken>()
        BeamNfc.sessionPublisher = SessionPublisher {
            SessionToken.mint(DeviceInfo("r", "R"), listOf(TransportId.NEARBY)).also(tokens::add)
        }

        repeat(3) {
            send(BeamApdu.selectCommand())
            send(BeamApdu.getSessionCommand())
            service.onDeactivated(android.nfc.cardemulation.HostApduService.DEACTIVATION_LINK_LOSS)
        }

        assertThat(tokens.map { it.advertisingName }.toSet()).hasSize(3)
    }

    @Test
    fun `the field dropping ends the selection`() {
        send(BeamApdu.selectCommand())
        service.onDeactivated(android.nfc.cardemulation.HostApduService.DEACTIVATION_LINK_LOSS)

        // A reader that comes back must select again before it gets anything.
        assertThat(send(BeamApdu.getSessionCommand())).isEqualTo(BeamApdu.SW_CONDITIONS_NOT_SATISFIED)
    }

    @Test
    fun `the advertised AID matches what the reader selects`() {
        assertThat(BeamApdu.AID_HEX).isEqualTo("F04265616D01")
        assertThat(BeamApdu.isSelectAid(BeamApdu.selectCommand())).isTrue()
    }
}
