package dev.beam

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The switches a beam depends on, and whether they are on.
 *
 * Permissions were the only thing Beam checked, so a phone with Wi-Fi off was told it was
 * ready, accepted a tap, and failed afterwards as "Could not create a Wi-Fi Direct group" -
 * by which point two people were already holding their phones together.
 *
 * These are deliberately **advisory and never blocking**. A permission Beam does not hold is a
 * fact; a radio being off is an inference about what the transfer will need, and the transports
 * do not all need the same things. Getting one of these wrong should cost a warning, not a
 * working phone — so nothing here feeds [BeamPermissions.isReady], and a check that cannot be
 * made reports nothing rather than guessing.
 */
object BeamRadios {

    /** A switch that is off, paired with somewhere the user can go and turn it on. */
    enum class Radio { NFC, WIFI, BLUETOOTH, LOCATION }

    /**
     * Whether this phone has NFC hardware at all.
     *
     * Distinct from NFC being switched off: there is no advice to give, and the app cannot
     * work here in either direction.
     */
    fun hasNfc(context: Context): Boolean = NfcAdapter.getDefaultAdapter(context) != null

    fun isNfcOn(context: Context): Boolean =
        NfcAdapter.getDefaultAdapter(context)?.isEnabled == true

    fun isWifiOn(context: Context): Boolean =
        ContextCompat.getSystemService(context, WifiManager::class.java)?.isWifiEnabled == true

    /**
     * Null when it cannot be known.
     *
     * Reading the adapter needs BLUETOOTH_CONNECT from Android 12, and this is called from the
     * screen whose whole job is that the permission may be missing. Throwing there, or
     * reporting "off" because we were not allowed to look, would both be worse than silence.
     */
    fun isBluetoothOn(context: Context): Boolean? = runCatching {
        ContextCompat.getSystemService(context, BluetoothManager::class.java)
            ?.adapter?.isEnabled
    }.getOrNull()

    /**
     * Everything that is off and worth mentioning, in the order it bites.
     *
     * Location is included on every version, not just where the permission model demands it:
     * Wi-Fi Direct discovery is reported to come back empty with the toggle off on several
     * OEM builds even when NEARBY_WIFI_DEVICES makes the permission unnecessary. As an
     * advisory that costs nothing; as a gate it would have blocked phones that work.
     */
    fun off(context: Context): List<Radio> = buildList {
        if (hasNfc(context) && !isNfcOn(context)) add(Radio.NFC)
        if (!isWifiOn(context)) add(Radio.WIFI)
        if (!BeamPermissions.isLocationServiceOn(context)) add(Radio.LOCATION)
        // Only the Nearby fallback needs Bluetooth; Wi-Fi Direct does not touch it. Last in
        // the list, and worded as the fallback it is.
        if (isBluetoothOn(context) == false) add(Radio.BLUETOOTH)
    }

    /** Where to send someone to turn [radio] back on. */
    fun settingsIntent(radio: Radio): Intent = when (radio) {
        Radio.NFC -> Intent(Settings.ACTION_NFC_SETTINGS)
        // The inline panel rather than the settings app: an app has not been allowed to switch
        // Wi-Fi on by itself since Android 10, and this at least keeps the user in place.
        Radio.WIFI -> Intent(Settings.Panel.ACTION_WIFI)
        Radio.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        Radio.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
