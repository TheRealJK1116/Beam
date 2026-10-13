package dev.beam

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The switches Beam warns about, and where it sends you to flip them.
 *
 * The intent mapping is worth pinning precisely because a wrong action string fails silently:
 * `startActivity` is wrapped in `runCatching`, so a typo produces a button that does nothing
 * at all rather than a crash anyone would notice.
 */
@RunWith(RobolectricTestRunner::class)
class BeamRadiosTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `each switch points at the settings screen that owns it`() {
        val expected = mapOf(
            BeamRadios.Radio.NFC to Settings.ACTION_NFC_SETTINGS,
            BeamRadios.Radio.WIFI to Settings.Panel.ACTION_WIFI,
            BeamRadios.Radio.BLUETOOTH to Settings.ACTION_BLUETOOTH_SETTINGS,
            BeamRadios.Radio.LOCATION to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        )

        // Over the enum rather than the map, so a new radio cannot be added without being
        // given somewhere to go.
        BeamRadios.Radio.entries.forEach { radio ->
            assertThat(BeamRadios.settingsIntent(radio).action).isEqualTo(expected.getValue(radio))
        }
    }

    @Test
    fun `every settings intent can be launched from outside an activity`() {
        // These are started from a Context that is not an Activity. Without NEW_TASK that is
        // an immediate crash - caught by runCatching, so the button would just do nothing.
        BeamRadios.Radio.entries.forEach { radio ->
            val flags = BeamRadios.settingsIntent(radio).flags
            assertThat(flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        }
    }

    @Test
    fun `bluetooth is mentioned last, because only the fallback needs it`() {
        // Wi-Fi Direct never touches Bluetooth, so leading with it would send people to fix
        // the one thing that was not going to stop their beam.
        val order = BeamRadios.off(context)
        if (BeamRadios.Radio.BLUETOOTH in order) {
            assertThat(order.last()).isEqualTo(BeamRadios.Radio.BLUETOOTH)
        }
    }

    @Test
    fun `a switch is never reported twice`() {
        val off = BeamRadios.off(context)
        assertThat(off).containsNoDuplicates()
    }

    @Test
    fun `nfc is only ever reported as off on a phone that has it`() {
        // On hardware without NFC there is no setting to send anyone to, and a separate
        // message says so. Listing "NFC is off" there would be advice with no action.
        if (!BeamRadios.hasNfc(context)) {
            assertThat(BeamRadios.off(context)).doesNotContain(BeamRadios.Radio.NFC)
        }
    }

    // --- what the warnings actually key off ---------------------------------------------

    private fun setWifi(on: Boolean) {
        context.getSystemService(WifiManager::class.java)!!.isWifiEnabled = on
    }

    private fun setLocation(on: Boolean) {
        shadowOf(context.getSystemService(LocationManager::class.java)).setLocationEnabled(on)
    }

    private fun setBluetooth(on: Boolean) {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(on)
    }

    @Test
    fun `wifi off is reported, because the files cross over wifi direct`() {
        setWifi(false)
        assertThat(BeamRadios.isWifiOn(context)).isFalse()
        assertThat(BeamRadios.off(context)).contains(BeamRadios.Radio.WIFI)
    }

    @Test
    fun `wifi on is not reported`() {
        setWifi(true)
        assertThat(BeamRadios.isWifiOn(context)).isTrue()
        assertThat(BeamRadios.off(context)).doesNotContain(BeamRadios.Radio.WIFI)
    }

    @Test
    fun `location off is reported on this platform too`() {
        // Deliberately not conditioned on the SDK level. The permission model says location is
        // irrelevant from Android 13, but discovery is reported to come back empty without it
        // on several OEM builds, and warning costs nothing where blocking would have cost a
        // working phone.
        setLocation(false)
        assertThat(BeamRadios.off(context)).contains(BeamRadios.Radio.LOCATION)
    }

    @Test
    fun `location on is not reported`() {
        setLocation(true)
        assertThat(BeamRadios.off(context)).doesNotContain(BeamRadios.Radio.LOCATION)
    }

    @Test
    fun `bluetooth off is reported`() {
        setBluetooth(false)
        assertThat(BeamRadios.isBluetoothOn(context)).isFalse()
        assertThat(BeamRadios.off(context)).contains(BeamRadios.Radio.BLUETOOTH)
    }

    @Test
    fun `bluetooth on is not reported`() {
        setBluetooth(true)
        assertThat(BeamRadios.isBluetoothOn(context)).isTrue()
        assertThat(BeamRadios.off(context)).doesNotContain(BeamRadios.Radio.BLUETOOTH)
    }

    @Test
    fun `a phone with everything on is told nothing`() {
        setWifi(true)
        setLocation(true)
        setBluetooth(true)
        // NFC is left out of this: Robolectric's device has no NFC hardware, which the
        // no-hardware message covers rather than this list.
        assertThat(BeamRadios.off(context)).isEmpty()
    }

    @Test
    fun `switches are reported independently`() {
        setWifi(false)
        setLocation(true)
        setBluetooth(true)

        val off = BeamRadios.off(context)
        assertThat(off).containsExactly(BeamRadios.Radio.WIFI)
    }
}
