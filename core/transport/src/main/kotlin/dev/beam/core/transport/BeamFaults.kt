package dev.beam.core.transport

import java.io.IOException

/**
 * A deliberate interruption, for testing the paths that only a bad day produces.
 *
 * Resume exists for a transfer that dies partway, which is precisely the thing that is hard
 * to stage on demand: pulling two phones apart mid-beam is imprecise, and the interesting
 * case — enough bytes landed to be worth keeping — is narrow. This lets a debug build cut a
 * transfer at an exact offset and then watch the next attempt pick it up.
 *
 * Nothing in the shipping app ever arms this. The Dev screen is the only caller and it exists
 * in debug builds alone, so in release the flag is read, found null, and ignored.
 */
object BeamFaults {

    @Volatile
    private var abortSendAfter: Long? = null

    /** Arms a single interruption: the next file being sent dies after [bytes]. */
    fun armSendInterruption(bytes: Long) {
        abortSendAfter = bytes.coerceAtLeast(1)
    }

    fun disarm() {
        abortSendAfter = null
    }

    val isArmed: Boolean get() = abortSendAfter != null

    /**
     * Called as a file streams out. Throws once, then disarms, so a resumed attempt is free
     * to succeed — which is the half of the behaviour actually being tested.
     */
    fun checkSendProgress(bytesSent: Long) {
        val limit = abortSendAfter ?: return
        if (bytesSent >= limit) {
            abortSendAfter = null
            throw IOException("Simulated interruption after $bytesSent bytes")
        }
    }
}
