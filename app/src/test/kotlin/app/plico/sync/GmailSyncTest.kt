package app.plico.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure Gmail rules; the sync itself is tested on the device (androidTest GmailSyncDeviceTest). */
class GmailSyncTest {
    @Test
    fun oneOredCategorySearch() {
        assertEquals("category:primary", GmailSync.categoryQuery(listOf("primary")))
        assertEquals("category:primary OR category:updates", GmailSync.categoryQuery(listOf("primary", "updates")))
        assertEquals(listOf("primary", "social", "promotions", "updates", "forums"), GmailSync.CATEGORIES)
    }
}
