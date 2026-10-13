package dev.beam

import android.content.Context

/**
 * Remembers that a tap had to be turned away, so the next person to open Beam finds out.
 *
 * When a phone taps this one and the permissions are not in place, the tap is declined on a
 * binder thread with no UI anywhere — [BeamContainer.mintSessionForTap] can only post a
 * notification saying so. That notification is itself a permission, and the case that matters
 * is precisely the one where the user has granted nothing: the tap then disappeared entirely,
 * leaving a line in logcat and two confused people holding phones together.
 *
 * Writing it down costs nothing and means the app can own up the next time it is opened,
 * whatever the notification settings happen to be.
 *
 * SharedPreferences rather than DataStore for the same reason as [DeviceIdentity]: the write
 * happens on a binder thread in a process that may be about to go away, and there is nowhere
 * to suspend.
 */
class DeclinedTaps(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("beam_declined_taps", Context.MODE_PRIVATE)

    /** True if a tap has been turned away since the user last saw the explanation. */
    val hasUnseen: Boolean get() = prefs.getBoolean(KEY, false)

    fun record() {
        // commit, not apply: the process is answering NFC and may not live long enough for an
        // asynchronous write to land.
        prefs.edit().putBoolean(KEY, true).commit()
    }

    fun markSeen() {
        prefs.edit().putBoolean(KEY, false).apply()
    }

    private companion object {
        const val KEY = "declined_since_seen"
    }
}
