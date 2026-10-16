package app.plico.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Column formats and small rules that the DAOs keep in pure functions. */
class HelpersTest {
    @Test
    fun defaultLabelsTakeTheLowestFreeNumber() {
        assertEquals("Riseup", AccountDao.nextLabel(Provider.RISEUP, emptySet()))
        assertEquals("Gmail", AccountDao.nextLabel(Provider.GMAIL, setOf("Riseup")))
        assertEquals("Riseup 2", AccountDao.nextLabel(Provider.RISEUP, setOf("Riseup")))
        assertEquals("Riseup 3", AccountDao.nextLabel(Provider.RISEUP, setOf("Riseup", "Riseup 2", "Riseup 4")))
        assertEquals("Gmail", AccountDao.nextLabel(Provider.GMAIL, setOf("Gmail 2", "Work")))
    }

    @Test
    fun gmailCategoriesRoundTrip() {
        val set = setOf("primary", "updates", "forums")
        assertEquals(set, AccountDao.parseCategories(AccountDao.formatCategories(set)))
        assertEquals(setOf("primary"), AccountDao.parseCategories("primary"))
        assertEquals(setOf("primary", "social"), AccountDao.parseCategories(" primary , social,,"))
        assertEquals(emptySet(), AccountDao.parseCategories(""))
        assertEquals("", AccountDao.formatCategories(emptySet()))
    }

    @Test
    fun opMessageIdsRoundTrip() {
        assertEquals("1,22,333", OpDao.formatIds(listOf(1, 22, 333)))
        assertEquals(listOf(1L, 22L, 333L), OpDao.parseIds("1,22,333"))
        assertEquals(emptyList(), OpDao.parseIds(""))
        assertEquals(listOf(4L, 5L), OpDao.parseIds(" 4, x,5,"))
        assertEquals(emptyList(), OpDao.parseIds(OpDao.formatIds(emptyList())))
    }

    @Test
    fun draftAttachmentsRoundTripAsJson() {
        val list = listOf(
            DraftAttachment("photo.jpg", "Photo \"1\".jpg", "image/jpeg", 1234),
            DraftAttachment("2-photo.jpg", "../evil\u0000.jpg", "image/jpeg", 3_000_000_000),
        )
        assertEquals(list, DraftDao.decodeAttachments(DraftDao.encodeAttachments(list)))
        assertEquals("[]", DraftDao.encodeAttachments(emptyList()))
        assertEquals(emptyList(), DraftDao.decodeAttachments("[]"))
        assertEquals(
            listOf(DraftAttachment("a", "b", "c", 7)),
            DraftDao.decodeAttachments("""[{"file":"a","name":"b","mime":"c","size":7}]"""),
        )
    }

    @Test
    fun contactPrefixPatternEscapesWildcards() {
        assertEquals("ann%", ContactDao.likePrefix("ann"))
        assertEquals("carl\\_%", ContactDao.likePrefix("carl_"))
        assertEquals("100\\%%", ContactDao.likePrefix("100%"))
        assertEquals("a\\\\b%", ContactDao.likePrefix("a\\b"))
        assertNull(ContactDao.likePrefix(""))
    }
}
