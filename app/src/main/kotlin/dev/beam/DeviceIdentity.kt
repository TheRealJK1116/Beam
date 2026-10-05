package dev.beam

import android.content.Context
import java.util.UUID

/**
 * Beam's durable identity on this install, readable the instant it is needed.
 *
 * This is deliberately not in DataStore with the rest of the settings. A tap is answered on a
 * binder thread in a cold process, with no opportunity to suspend and wait for a flow to
 * emit, so an asynchronously-loaded id is not available when the token is minted. It used to
 * fall back to the literal string "pending", which two different phones would then both claim
 * — and since resume state is keyed by peer, two devices calling themselves the same thing
 * could have their partial transfers confused for one another.
 *
 * SharedPreferences is the right tool for exactly this: a small value, loaded into memory
 * before first access, readable without suspending.
 *
 * The id is random per install, not derived from any hardware identifier. It exists so a
 * device can be recognised across taps ("accept from devices I've accepted before") and
 * nowhere else, and it only ever leaves the phone inside a session token handed over NFC.
 */
class DeviceIdentity(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("beam_identity", Context.MODE_PRIVATE)

    val stableId: String
        get() = prefs.getString(KEY, null) ?: synchronized(this) {
            // Re-read inside the lock: two threads can race here on a cold start.
            prefs.getString(KEY, null) ?: mint().also {
                prefs.edit().putString(KEY, it).apply()
            }
        }

    /** 22 characters, inside the session token's 24-byte cap for this field. */
    private fun mint(): String = UUID.randomUUID().toString().replace("-", "").take(22)

    private companion object {
        const val KEY = "stable_id"
    }
}
