package app.plico.html

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncomingUrisTest {
    private val own = "app.plico"

    @Test
    fun contentFromOtherApps() {
        assertTrue(IncomingUris.allowed("content", "com.android.providers.media.documents", own, "com.android.providers.media.module"))
        assertTrue(IncomingUris.allowed("content", "media", own, "com.android.providers.media.module"))
        // No provider found: allowed here, the copy fails later.
        assertTrue(IncomingUris.allowed("content", "com.example.gone", own, null))
    }

    @Test
    fun plicoOwnProviderIsRejected() {
        assertFalse(IncomingUris.allowed("content", "app.plico.files", own, own))
        // "0@" selects a user; the content resolver strips it and finds Plico's provider.
        assertFalse(IncomingUris.allowed("content", "0@app.plico.files", own, null))
        assertFalse(IncomingUris.allowed("content", "app.plico.files:80", own, null))
    }

    @Test
    fun otherSchemesAreRejected() {
        assertFalse(IncomingUris.allowed("file", "", own, null))
        assertFalse(IncomingUris.allowed("file", null, own, null))
        assertFalse(IncomingUris.allowed("android.resource", "app.plico", own, null))
        assertFalse(IncomingUris.allowed("https", "example.com", own, null))
        assertFalse(IncomingUris.allowed(null, "com.example.provider", own, "com.example"))
        assertFalse(IncomingUris.allowed("content", null, own, null))
        assertFalse(IncomingUris.allowed("content", "", own, null))
    }
}
