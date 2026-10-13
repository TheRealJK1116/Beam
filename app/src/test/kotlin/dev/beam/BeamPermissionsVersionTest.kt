package dev.beam

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which permissions Beam insists on, per platform version.
 *
 * These rules are worth pinning because getting one wrong does not fail loudly: it produces an
 * app that asks for permissions, receives every one of them, and then says it does not have
 * them — with no way out from inside the app, because the gate is waiting on something the
 * platform will never hand over.
 */
class BeamPermissionsVersionTest {

    private val localNetwork = BeamPermissions.ACCESS_LOCAL_NETWORK

    @Test
    fun `android 16 does not require the local network permission`() {
        // The bug this exists to prevent. On Android 16 local network protections are opt-in:
        // the permission is not requestable, no dialog ever offers it, and the check reports
        // it denied permanently. Requiring it there is a gate with no key.
        assertThat(BeamPermissions.requiredFor(36)).doesNotContain(localNetwork)
    }

    @Test
    fun `android 17 does require it, because that is where it is enforced`() {
        assertThat(BeamPermissions.requiredFor(37)).contains(localNetwork)
    }

    @Test
    fun `nothing before android 17 requires it`() {
        for (sdk in 31..36) {
            assertThat(BeamPermissions.requiredFor(sdk)).doesNotContain(localNetwork)
        }
    }

    @Test
    fun `android 13 and up ask for nearby wifi rather than location`() {
        val required = BeamPermissions.requiredFor(33)
        assertThat(required).contains(Manifest.permission.NEARBY_WIFI_DEVICES)
        assertThat(required).doesNotContain(Manifest.permission.ACCESS_FINE_LOCATION)
        assertThat(required).doesNotContain(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @Test
    fun `android 12 asks for location, which is the only route it has`() {
        val required = BeamPermissions.requiredFor(31)
        assertThat(required).contains(Manifest.permission.ACCESS_FINE_LOCATION)
        assertThat(required).contains(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertThat(required).doesNotContain(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    @Test
    fun `every version needs the bluetooth trio, whatever else changes`() {
        for (sdk in 31..37) {
            assertThat(BeamPermissions.requiredFor(sdk)).containsAtLeast(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        }
    }

    /**
     * The location permissions carry maxSdkVersion="32" in the manifest, so asking for them
     * above that would be asking for something the install does not even contain.
     */
    @Test
    fun `location is never required above the version the manifest declares it for`() {
        for (sdk in 33..37) {
            val required = BeamPermissions.requiredFor(sdk)
            assertThat(required).doesNotContain(Manifest.permission.ACCESS_FINE_LOCATION)
            assertThat(required).doesNotContain(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}
