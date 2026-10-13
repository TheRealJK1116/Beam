package dev.beam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The note Beam leaves itself when it has to turn a tap away.
 *
 * This exists because the notification that was supposed to explain a declined tap needs
 * POST_NOTIFICATIONS, and the user who has granted nothing is precisely the one whose taps get
 * declined — so the tap vanished entirely. Everything here is about the message surviving long
 * enough to be delivered.
 */
@RunWith(RobolectricTestRunner::class)
class DeclinedTapsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun fresh() = DeclinedTaps(context).also { it.markSeen() }

    @Test
    fun `nothing to report before anything has happened`() {
        assertThat(fresh().hasUnseen).isFalse()
    }

    @Test
    fun `a declined tap is remembered`() {
        val taps = fresh()
        taps.record()
        assertThat(taps.hasUnseen).isTrue()
    }

    @Test
    fun `it survives the process that wrote it`() {
        // The whole point: the tap is declined on a binder thread in a process that may be
        // gone moments later, and the person is told when they next open the app. A new
        // instance stands in for that later launch.
        fresh().record()
        assertThat(DeclinedTaps(context).hasUnseen).isTrue()
    }

    @Test
    fun `it stops being reported once the user has been told`() {
        val taps = fresh()
        taps.record()
        taps.markSeen()

        assertThat(taps.hasUnseen).isFalse()
        assertThat(DeclinedTaps(context).hasUnseen).isFalse()
    }

    @Test
    fun `several declined taps still only ask to be explained once`() {
        val taps = fresh()
        repeat(5) { taps.record() }
        taps.markSeen()

        // Not a counter: a phone left on a desk could collect dozens, and the user needs the
        // explanation once, not a number.
        assertThat(taps.hasUnseen).isFalse()
    }

    @Test
    fun `a tap declined after the explanation is reported again`() {
        val taps = fresh()
        taps.record()
        taps.markSeen()
        taps.record()

        assertThat(taps.hasUnseen).isTrue()
    }
}
