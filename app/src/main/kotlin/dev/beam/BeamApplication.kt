package dev.beam

import android.app.Application
import dev.beam.feature.nfc.BeamNfc
import dev.beam.feature.nfc.SessionPublisher

/**
 * Beam's process entry point — and, critically, the one the OS runs when it binds
 * [dev.beam.feature.nfc.BeamHceService] because another phone tapped this one. Receiving
 * therefore works from a cold process with the app never opened, which is the whole premise.
 *
 * Everything here must stay cheap: it is on the critical path of a tap.
 */
class BeamApplication : Application() {

    lateinit var container: BeamContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = BeamContainer(this)
        container.warmUp()

        BeamTransferService.createChannels(this)

        // The NFC layer's only link to the rest of the app.
        BeamNfc.sessionPublisher = SessionPublisher {
            container.mintSessionForTap()?.also { token ->
                // Publish before answering: by the time the sender has decoded the token and
                // started scanning, the advertisement is already up.
                BeamTransferService.startReceiving(this, token)
            }
        }
    }
}
