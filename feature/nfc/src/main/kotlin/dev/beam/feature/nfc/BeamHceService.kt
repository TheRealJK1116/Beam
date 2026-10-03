package dev.beam.feature.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import dev.beam.core.session.SessionToken
import dev.beam.core.session.SessionTokenCodec

/**
 * The receiving half of the tap.
 *
 * Declared in the manifest with Beam's AID, which is the entire reason receiving works with the
 * app closed: the OS binds this service when a reader selects that AID, starting Beam's process
 * if it is not already running. No boot receiver, no persistent background service, no
 * always-on advertising.
 *
 * The contract with the rest of the app is one callback, [SessionPublisher], registered by the
 * Application. This service knows nothing about transports — it mints the token, asks the app to
 * start publishing, and hands the token back over NFC.
 *
 * Everything here runs on a binder thread while the phones are physically touching, so it does
 * the minimum and returns: minting is a couple of `SecureRandom` reads, and starting the
 * advertising is fire-and-forget.
 */
class BeamHceService : HostApduService() {

    /** True between a successful SELECT and the deactivation of the field. */
    private var selected = false

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val apdu = commandApdu ?: return BeamApdu.SW_UNKNOWN

        return when {
            BeamApdu.isSelectAid(apdu) -> {
                selected = true
                BeamApdu.SW_OK
            }

            BeamApdu.isGetSession(apdu) -> {
                if (!selected) BeamApdu.SW_CONDITIONS_NOT_SATISFIED else respondWithSession()
            }

            // A reader that selected some other application has nothing to say to us.
            apdu.size >= 2 && apdu[1] == 0xA4.toByte() -> BeamApdu.SW_FILE_NOT_FOUND

            else -> BeamApdu.SW_INS_NOT_SUPPORTED
        }
    }

    private fun respondWithSession(): ByteArray {
        val publisher = BeamNfc.sessionPublisher
        if (publisher == null) {
            Log.w(TAG, "Tapped, but no session publisher is registered")
            return BeamApdu.SW_CONDITIONS_NOT_SATISFIED
        }

        val token: SessionToken = try {
            publisher.publishSession()
        } catch (t: Throwable) {
            Log.e(TAG, "Publishing a session failed", t)
            null
        } ?: return BeamApdu.SW_CONDITIONS_NOT_SATISFIED // receiving is off, or no usable transport

        Log.i(TAG, "Tap answered: published ${token.advertisingName} offering ${token.transports}")
        return SessionTokenCodec.encode(token) + BeamApdu.SW_OK
    }

    override fun onDeactivated(reason: Int) {
        selected = false
        if (reason == DEACTIVATION_LINK_LOSS) {
            // The phones separated. The transfer, if one started, continues over its own radio;
            // nothing to clean up here.
            Log.d(TAG, "NFC link lost after tap")
        }
    }

    private companion object {
        const val TAG = "BeamHce"
    }
}

/**
 * How the NFC layer asks the rest of the app for a session, without knowing what a transport is.
 *
 * The app's implementation mints a token for the backends it can actually honour right now and
 * starts advertising under it. Returning null declines the tap — receiving is switched off, or
 * no transport is available.
 */
fun interface SessionPublisher {
    /** Called on a binder thread during the tap. Must return promptly. */
    fun publishSession(): SessionToken?
}

/**
 * The single registration point, set from `Application.onCreate` so that it is in place however
 * the process was started — including by the OS binding [BeamHceService] to a tap.
 */
object BeamNfc {
    @Volatile
    var sessionPublisher: SessionPublisher? = null
}
