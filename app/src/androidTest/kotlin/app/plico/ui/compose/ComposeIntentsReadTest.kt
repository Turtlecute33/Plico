package app.plico.ui.compose

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import android.text.SpannableString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Reading share and mailto intents with the platform's Intent, Bundle and ClipData (DESIGN §11.6). */
class ComposeIntentsReadTest {
    private val a = Uri.parse("content://com.example.files/a")
    private val b = Uri.parse("content://com.example.files/b")

    /** Extras from another app arrive parcelled; this reads them the same way. */
    private fun fromOtherApp(intent: Intent): Intent {
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun read(intent: Intent) = assertNotNull(ComposeIntents.read(fromOtherApp(intent)))

    @Test
    fun sendReadsExtrasAndStream() {
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, a)
            .putExtra(Intent.EXTRA_EMAIL, arrayOf("x@example.com"))
            .putExtra(Intent.EXTRA_CC, arrayOf("y@example.com"))
            .putExtra(Intent.EXTRA_BCC, arrayOf("z@example.com"))
            .putExtra(Intent.EXTRA_SUBJECT, "Subject")
            .putExtra(Intent.EXTRA_TEXT, SpannableString("Text"))
        // startActivity copies EXTRA_STREAM into ClipData; the URI must not be attached twice.
        intent.clipData = ClipData.newRawUri(null, a)
        val (shared, streams) = read(intent)
        assertEquals(listOf(a), streams)
        assertEquals(
            ComposeIntents.Shared(
                to = listOf("x@example.com"),
                cc = listOf("y@example.com"),
                bcc = listOf("z@example.com"),
                subject = "Subject",
                text = "Text",
            ),
            shared,
        )
    }

    @Test
    fun sendMultipleReadsListAndClipData() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
        intent.clipData = ClipData.newRawUri(null, b).apply { addItem(ClipData.Item(Uri.parse("content://other/c"))) }
        assertEquals(listOf(a, b, Uri.parse("content://other/c")), read(intent).second)
    }

    @Test
    fun wrongExtraTypesAreIgnored() {
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, "content://com.example.files/a")
            .putExtra(Intent.EXTRA_EMAIL, "single@example.com")
            .putExtra(Intent.EXTRA_CC, 42)
            .putExtra(Intent.EXTRA_SUBJECT, 7L)
        val (shared, streams) = read(intent)
        assertEquals(emptyList(), streams)
        assertEquals(listOf("single@example.com"), shared.to)
        assertEquals(emptyList(), shared.cc)
        assertNull(shared.subject)
    }

    @Test
    fun sendtoReadsMailtoAndTextExtras() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:a@example.com"))
            .putExtra(Intent.EXTRA_SUBJECT, "Hello")
            .putExtra(Intent.EXTRA_STREAM, a)
        val (shared, streams) = read(intent)
        assertEquals("mailto:a@example.com", shared.mailto)
        assertEquals("Hello", shared.subject)
        assertEquals(emptyList(), streams)
    }

    @Test
    fun viewReadsOnlyTheMailtoUri() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:a@example.com?subject=Hi"))
            .putExtra(Intent.EXTRA_SUBJECT, "Ignored")
            .putExtra(Intent.EXTRA_STREAM, a)
        assertEquals(ComposeIntents.Shared(mailto = "mailto:a@example.com?subject=Hi") to emptyList(), read(intent))
    }

    @Test
    fun otherIntentsAreNotRead() {
        assertNull(ComposeIntents.read(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))))
        assertNull(ComposeIntents.read(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:123"))))
        assertNull(ComposeIntents.read(Intent(Intent.ACTION_MAIN)))
    }
}
