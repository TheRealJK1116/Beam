package dev.beam

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The permission checks as they behave against a real package manager.
 *
 * [BeamPermissionsVersionTest] covers which permissions are wanted at which platform version;
 * this covers what Beam concludes once a device has answered.
 */
@RunWith(RobolectricTestRunner::class)
class BeamPermissionsDeviceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun grant(vararg permissions: String) {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(*permissions)
    }

    private fun deny(vararg permissions: String) {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(*permissions)
    }

    @Test
    fun `a permission this platform has never heard of is not required`() {
        // The backstop for the bug that started this: requiring something the platform cannot
        // grant is a gate with no key, and the app is then unusable with no way out from
        // inside it. Better to lose a capability than everything.
        assertThat(BeamPermissions.requiredForTransfer(context))
            .doesNotContain("dev.beam.test.A_PERMISSION_THAT_DOES_NOT_EXIST")
        assertThat(BeamPermissions.requiredForTransfer(context))
            .doesNotContain(BeamPermissions.ACCESS_LOCAL_NETWORK)
    }

    @Test
    fun `the required set is never empty`() {
        // The invariant the first version of the grantability check broke. Filtering is
        // allowed to drop a permission; it is not allowed to drop all of them, because an
        // empty required set means canTransfer() is trivially true and Beam declares itself
        // ready for a beam it cannot make. A gate stuck shut is visible; a gate stuck open
        // lies to the user and fails at the tap.
        assertThat(BeamPermissions.requiredForTransfer(context)).isNotEmpty()
        assertThat(BeamPermissions.allRequestable(context)).isNotEmpty()
    }

    @Test
    fun `granting everything required is enough to transfer`() {
        grant(*BeamPermissions.requiredForTransfer(context).toTypedArray())

        assertThat(BeamPermissions.missingForTransfer(context)).isEmpty()
        assertThat(BeamPermissions.canTransfer(context)).isTrue()
    }

    @Test
    fun `withholding one of them is enough to stop it`() {
        grant(*BeamPermissions.requiredForTransfer(context).toTypedArray())
        deny(Manifest.permission.BLUETOOTH_CONNECT)

        assertThat(BeamPermissions.missingForTransfer(context))
            .containsExactly(Manifest.permission.BLUETOOTH_CONNECT)
        assertThat(BeamPermissions.canTransfer(context)).isFalse()
    }

    @Test
    fun `notifications are wanted but never required`() {
        // A beam completes without them; it just loses the progress readout. Requiring them
        // would make a refused notification prompt stop the app working altogether.
        grant(*BeamPermissions.requiredForTransfer(context).toTypedArray())
        deny(Manifest.permission.POST_NOTIFICATIONS)

        assertThat(BeamPermissions.canTransfer(context)).isTrue()
        assertThat(BeamPermissions.missingRequestable(context))
            .contains(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `everything required is also something we ask for`() {
        val requestable = BeamPermissions.allRequestable(context)
        assertThat(requestable).containsAtLeastElementsIn(BeamPermissions.requiredForTransfer(context))
    }

    @Test
    @Config(sdk = [32])
    fun `approximate location is recognised as not good enough`() {
        // Android 12's dialog grants coarse and denies fine when the user picks Approximate,
        // and will keep offering the same two buttons however often Beam asks. Spotting it is
        // the only thing that turns a loop into an instruction.
        deny(Manifest.permission.ACCESS_FINE_LOCATION)
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertThat(BeamPermissions.needsPreciseLocation(context)).isTrue()
        assertThat(BeamPermissions.canTransfer(context)).isFalse()
    }

    @Test
    @Config(sdk = [32])
    fun `precise location clears it`() {
        grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        assertThat(BeamPermissions.needsPreciseLocation(context)).isFalse()
    }

    @Test
    @Config(sdk = [32])
    fun `refusing location outright is not mistaken for choosing approximate`() {
        // Both leave fine denied, but they need different advice: one person has a button to
        // press, the other needs telling which of two buttons is the right one.
        deny(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        assertThat(BeamPermissions.needsPreciseLocation(context)).isFalse()
    }

    @Test
    fun `the approximate trap cannot be hit on android 13 and up`() {
        // There is no location permission in play at all above Android 12, so advice about
        // precise location would be nonsense. Default Robolectric SDK is 34.
        deny(Manifest.permission.ACCESS_FINE_LOCATION)
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertThat(BeamPermissions.needsPreciseLocation(context)).isFalse()
    }
}
