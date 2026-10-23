package dev.beam

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Working out what a received file is.
 *
 * The case that prompted all this: a beamed APK could not be opened at all. Everything Beam
 * received was offered to the system as a wildcard type, and while a gallery will take a punt
 * on an unknown file, the package installer will not.
 */
@RunWith(RobolectricTestRunner::class)
class ReceivedFileTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver get() = context.contentResolver

    /** What the sink produces: a MediaStore URI, which has no extension anywhere in it. */
    private val mediaStoreUri: Uri = Uri.parse("content://dev.beam.test.media/downloads/1234")

    /**
     * Stands in for MediaStore, which is where received files live and which is asked what
     * they are. Only getType matters here.
     */
    class FakeMediaProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = type
        override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

        companion object { var type: String? = null }
    }

    private fun storedAs(type: String?) {
        FakeMediaProvider.type = type
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, "dev.beam.test.media")
    }

    @Test
    fun `the type recorded in MediaStore is believed`() {
        storedAs("image/jpeg")
        assertThat(ReceivedFile.mimeType(resolver, mediaStoreUri, "holiday.jpg"))
            .isEqualTo("image/jpeg")
    }

    @Test
    fun `an apk recorded properly is offered as an apk`() {
        storedAs(ReceivedFile.APK_MIME)
        assertThat(ReceivedFile.mimeType(resolver, mediaStoreUri, "beam.apk"))
            .isEqualTo(ReceivedFile.APK_MIME)
    }

    @Test
    fun `an apk stored as a generic blob is still recognised`() {
        // What happens when the sender could not identify the file: it is committed as
        // octet-stream, which nothing on the phone will open. The filename is the only
        // evidence left, and for an APK it is the difference between installable and inert.
        storedAs("application/octet-stream")
        assertThat(ReceivedFile.mimeType(resolver, mediaStoreUri, "beam.apk"))
            .isEqualTo(ReceivedFile.APK_MIME)
    }

    @Test
    fun `the filename rescues a file the resolver knows nothing about`() {
        storedAs(null)
        assertThat(ReceivedFile.mimeType(resolver, mediaStoreUri, "notes.pdf"))
            .isEqualTo("application/pdf")
    }

    @Test
    fun `the uri is never parsed for an extension, because it has none`() {
        // The original bug in one assertion. A MediaStore URI ends in a row id, so reading an
        // extension off it yields nothing useful and every file became a wildcard.
        storedAs(null)
        val type = ReceivedFile.mimeType(resolver, mediaStoreUri, "report.pdf")

        assertThat(type).isEqualTo("application/pdf")
        assertThat(type).isNotEqualTo("*/*")
    }

    @Test
    fun `a file with no type and no extension falls back to a wildcard`() {
        storedAs(null)
        // Honest last resort: let the system offer whatever it has.
        assertThat(ReceivedFile.mimeType(resolver, mediaStoreUri, "README"))
            .isEqualTo("*/*")
    }

    @Test
    fun `extension case does not matter`() {
        storedAs(null)
        assertThat(ReceivedFile.mimeType(resolver, mediaStoreUri, "INSTALLER.APK"))
            .isEqualTo(ReceivedFile.APK_MIME)
    }

    @Test
    fun `a resolver that throws does not take the open with it`() {
        // Opening a file should not crash the history screen because a provider misbehaved.
        val dead = Uri.parse("content://dev.beam.nonexistent/7")
        assertThat(ReceivedFile.mimeType(resolver, dead, "thing.pdf"))
            .isEqualTo("application/pdf")
    }
}
