package app.plico.ui.message

import app.plico.R
import app.plico.account.MailError
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MailView
import app.plico.html.Link
import app.plico.html.LinkCleaner
import app.plico.html.LinkWarning
import app.plico.html.Mailto
import app.plico.html.SenderTrust
import app.plico.html.SenderWarning
import app.plico.html.testPsl
import app.plico.mail.mime.Address
import app.plico.sync.DraftPrefill
import app.plico.ui.components.BannerKind
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageModelsTest {

    // ---- Header lines

    @Test
    fun senderShowsNameAndRealAddress() {
        assertEquals(SenderLine("Alice Example", "alice@example.com"), senderLine("Alice Example", "alice@example.com"))
        assertEquals(SenderLine("alice@example.com", null), senderLine("", "alice@example.com"))
        assertEquals(SenderLine("alice@example.com", null), senderLine("ALICE@example.com", "alice@example.com"))
        assertEquals(SenderLine("PayPal \uFFFDmoc", "x@evil.example"), senderLine("PayPal \u202Emoc", "x@evil.example"))
    }

    @Test
    fun recipientLinesSkipEmptyFields() {
        val lines = recipientLines("Alice <a@example.com>, b@example.com", "", "\"Carol, C.\" <c@example.com>")
        assertEquals(listOf(RecipientField.TO, RecipientField.BCC), lines.map { it.field })
        assertEquals(listOf("a@example.com", "b@example.com"), lines[0].addresses.map { it.email })
        assertEquals("Carol, C.", lines[1].addresses.single().name)
        assertEquals(emptyList(), recipientLines("", "", ""))
    }

    @Test
    fun summaryShowsMeAndCountsTheRest() {
        val own = setOf("me@riseup.net")
        val to = "Me <ME@riseup.net>, Alice <a@example.com>"
        val lines = recipientLines(to, "b@example.com, a@example.com, c@example.com", "")
        assertEquals(RecipientSummary(listOf(null, "Alice"), 2), recipientSummary(lines, own))
        val single = recipientLines("", "b@example.com", "")
        assertEquals(RecipientSummary(listOf("b@example.com"), 0), recipientSummary(single, own))
        assertNull(recipientSummary(emptyList(), own))
    }

    @Test
    fun replyToLineOnlyWhenItDiffersFromSender() {
        assertNull(replyToLine("", "news@example.com"))
        assertNull(replyToLine("News <NEWS@example.com>", "news@example.com"))
        val line = replyToLine("help@other.example", "news@example.com")
        assertEquals(RecipientField.REPLY_TO, line?.field)
        assertEquals("help@other.example", line?.addresses?.single()?.email)
    }

    @Test
    fun addressTextAndShortName() {
        assertEquals("Alice <a@example.com>", addressText(Address("Alice", "a@example.com")))
        assertEquals("a@example.com", addressText(Address(" ", "a@example.com")))
        assertEquals("a@example.com", shortName(Address(null, "a@example.com")))
        assertEquals("Bob", shortName(Address(" Bob ", "b@example.com")))
    }

    @Test
    fun replyAllOnlyWithMoreThanOneOtherAddress() {
        val own = setOf("me@riseup.net")
        assertFalse(replyAllUseful("a@example.com", "me@riseup.net", "", own))
        assertFalse(replyAllUseful("a@example.com", "Me <ME@riseup.net>, A <a@example.com>", "", own))
        assertTrue(replyAllUseful("a@example.com", "me@riseup.net", "b@example.com", own))
        assertTrue(replyAllUseful("me@riseup.net", "a@example.com, b@example.com", "", own))
    }

    // ---- Banners

    @Test
    fun bannersKeepOrderAndLeaveOutFirstContact() {
        val banners = senderBanners(
            listOf(
                SenderWarning.AUTH_FAILED,
                SenderWarning.NAME_HAS_OTHER_DOMAIN,
                SenderWarning.REPLY_TO_OTHER_DOMAIN,
                SenderWarning.FIRST_CONTACT,
            ),
        )
        assertEquals(
            listOf(
                SenderBanner(SenderWarning.AUTH_FAILED, BannerKind.ERROR),
                SenderBanner(SenderWarning.NAME_HAS_OTHER_DOMAIN, BannerKind.WARN),
                SenderBanner(SenderWarning.REPLY_TO_OTHER_DOMAIN, BannerKind.WARN),
            ),
            banners,
        )
        assertEquals(emptyList(), senderBanners(listOf(SenderWarning.FIRST_CONTACT)))
    }

    // ---- Placement

    private fun folder(
        id: Long,
        role: FolderRole?,
        kind: Int = FolderKind.MAILBOX,
        hidden: Boolean = false,
        name: String = "f$id",
    ) = Folder(id, 1, name, name, role, kind, "/", hidden, false, false, 0, 0, 0, 0, 0)

    @Test
    fun gmailPlacementUsesTheInboxLabel() {
        val all = folder(1, FolderRole.ALL)
        val inbox = folder(10, FolderRole.INBOX, FolderKind.LABEL, name = "Inbox")
        val important = folder(11, FolderRole.IMPORTANT, FolderKind.LABEL)
        val work = folder(12, null, FolderKind.LABEL, name = "Work")
        val hidden = folder(13, null, FolderKind.LABEL, hidden = true)
        val p = placementOf(1, listOf(all, inbox, important, work, hidden), gmail = true)
        assertTrue(p.inInbox)
        assertTrue(p.important)
        assertTrue(p.canArchive)
        assertEquals(listOf("Inbox", "Work"), p.labels.map { it.name })
        assertEquals(setOf(10L, 11L, 12L, 13L), p.labelIds)
        assertEquals(MailView.InFolder(10), p.view)

        val archived = placementOf(1, listOf(all, work), gmail = true)
        assertFalse(archived.canArchive)
        assertFalse(archived.important)
        assertEquals(MailView.InFolder(1), archived.view)

        val spam = placementOf(2, listOf(folder(2, FolderRole.JUNK)), gmail = true)
        assertTrue(spam.inJunk)
        assertTrue(spam.deletesForever)
    }

    @Test
    fun riseupPlacementByMailboxRole() {
        val inbox = placementOf(1, listOf(folder(1, FolderRole.INBOX)), gmail = false)
        assertTrue(inbox.inInbox)
        assertTrue(inbox.canArchive)
        assertFalse(inbox.deletesForever)
        assertEquals(emptyList(), inbox.labels)
        assertEquals(emptySet(), inbox.labelIds)
        assertEquals(MailView.InFolder(1), inbox.view)

        assertTrue(placementOf(5, listOf(folder(5, null)), gmail = false).canArchive)
        assertFalse(placementOf(2, listOf(folder(2, FolderRole.ARCHIVE)), gmail = false).canArchive)
        val trash = placementOf(3, listOf(folder(3, FolderRole.TRASH)), gmail = false)
        assertFalse(trash.canArchive)
        assertTrue(trash.deletesForever)
        assertTrue(placementOf(4, listOf(folder(4, FolderRole.SENT)), gmail = false).isOwnFolder)
    }

    // ---- Remote content

    @Test
    fun remoteAllowDecision() {
        fun allow(
            default: Boolean = false,
            once: Boolean = false,
            trusted: Boolean = false,
            trust: SenderTrust,
            junk: Boolean = false,
        ) = allowRemote(default, once, trusted, trust, junk)
        assertFalse(allow(trust = SenderTrust.VERIFIED))
        assertTrue(allow(once = true, trust = SenderTrust.NONE, junk = true))
        assertTrue(allow(default = true, trust = SenderTrust.NONE))
        // Never by default in Spam.
        assertFalse(allow(default = true, trust = SenderTrust.NONE, junk = true))
        assertTrue(allow(trusted = true, trust = SenderTrust.VERIFIED))
        assertTrue(allow(trusted = true, trust = SenderTrust.FROM_ONLY))
        // A trusted address whose message is not verified (Gmail without dmarc=pass) is not enough.
        assertFalse(allow(trusted = true, trust = SenderTrust.NONE))
    }

    @Test
    fun remoteBarStates() {
        fun bar(has: Boolean, allowed: Boolean, count: Int, trust: SenderTrust, trusted: Boolean, on: Boolean = false) =
            remoteBar(has, allowed, count, trust, trusted, defaultOn = on)
        assertEquals(RemoteBar(RemoteImages.BLOCKED, 3, false, null), bar(true, false, 3, SenderTrust.VERIFIED, false))
        assertEquals(
            RemoteBar(RemoteImages.SHOWN, 0, true, R.string.html_sender_trust_fakeable),
            bar(true, true, 0, SenderTrust.FROM_ONLY, true),
        )
        assertEquals(RemoteBar(RemoteImages.BLOCKED, 0, null, null), bar(true, false, 0, SenderTrust.NONE, true))
        assertEquals(RemoteBar(RemoteImages.NONE, 2, null, null), bar(false, false, 2, SenderTrust.VERIFIED, false))
        // Shown by the setting: no switch, so nothing to say.
        assertNull(bar(true, true, 0, SenderTrust.VERIFIED, false, on = true))
        assertNull(bar(false, false, 0, SenderTrust.VERIFIED, false))
    }

    // ---- Unsubscribe

    @Test
    fun unsubscribeMethod() {
        val both = "<mailto:leave@list.example?subject=unsubscribe>, <https://list.example/u/123>"
        assertEquals(Unsubscribe.OneClick, unsubscribeOf(both, post = true, trust = SenderTrust.VERIFIED))
        val mail = assertIs<Unsubscribe.Mail>(unsubscribeOf(both, post = true, trust = SenderTrust.FROM_ONLY))
        assertEquals("leave@list.example", mail.mailto.to.single().email)
        assertEquals("unsubscribe", mail.mailto.subject)
        val web = "https://list.example/u"
        assertEquals(Unsubscribe.Web(web), unsubscribeOf("<$web>", false, SenderTrust.VERIFIED))
        // One-click needs https.
        val plain = "http://list.example/u"
        assertEquals(Unsubscribe.Web(plain), unsubscribeOf("<$plain>", true, SenderTrust.VERIFIED))
        // Without angle brackets, and a mailto without an address.
        assertEquals(Unsubscribe.Web(web), unsubscribeOf("mailto:?subject=x, $web", false, SenderTrust.NONE))
        assertNull(unsubscribeOf("<ftp://list.example/u>", false, SenderTrust.NONE))
        assertNull(unsubscribeOf(null, true, SenderTrust.VERIFIED))
        assertNull(unsubscribeOf(" ", true, SenderTrust.VERIFIED))
    }

    @Test
    fun prefillFromMailto() {
        val mailto = Mailto.parse("mailto:a@example.com?cc=b@example.com&subject=Hi%20there&body=Line%0D%0Atwo")!!
        assertEquals(
            DraftPrefill(to = "a@example.com", cc = "b@example.com", subject = "Hi there", body = "Line\ntwo"),
            prefillOf(mailto),
        )
    }

    // ---- Link sheet

    private val cleaner = LinkCleaner(testPsl)

    @Test
    fun sheetShowsTheRegistrableDomainInTheUrl() {
        val link = assertIs<Link.Web>(cleaner.clean("https://www.example.co.uk/path?utm_source=news&id=7"))
        val sheet = linkSheet(link)
        assertEquals("example.co.uk", sheet.title)
        assertEquals("https://www.example.co.uk/path?id=7", sheet.url)
        assertEquals("example.co.uk", sheet.titleRange?.let { sheet.url.substring(it.first, it.last + 1) })
        assertNull(sheet.unicodeTitle)
        assertTrue(sheet.openable)
    }

    @Test
    fun sheetShowsPunycodeAndUnicode() {
        val sheet = linkSheet(assertIs<Link.Web>(cleaner.clean("https://bücher.de:8443/x")))
        assertEquals("xn--bcher-kva.de", sheet.title)
        assertEquals("bücher.de", sheet.unicodeTitle)
        assertEquals("https://xn--bcher-kva.de:8443/x", sheet.url)
        assertEquals("xn--bcher-kva.de", sheet.titleRange?.let { sheet.url.substring(it.first, it.last + 1) })
    }

    @Test
    fun sheetForIpHostsAndWarnings() {
        val link = assertIs<Link.Web>(cleaner.clean("http://192.168.1.1/login", "www.mybank.com"))
        val sheet = linkSheet(link)
        assertEquals("192.168.1.1", sheet.title)
        assertEquals(listOf(LinkWarning.OTHER_DOMAIN, LinkWarning.IP_HOST), sheet.warnings)
    }

    @Test
    fun tapDecision() {
        val plain = Link.Web("https://example.com/", "example.com", "example.com", emptySet(), null)
        assertEquals(LinkTap.Open("https://example.com/"), linkTap(plain, confirm = false))
        assertIs<LinkTap.Sheet>(linkTap(plain, confirm = true))
        val warned = plain.copy(warnings = setOf(LinkWarning.LOOKALIKE))
        assertIs<LinkTap.Sheet>(linkTap(warned, confirm = false))

        val mail = assertIs<LinkTap.Compose>(linkTap(cleaner.clean("mailto:a@example.com?subject=Hello"), true))
        assertEquals(DraftPrefill(to = "a@example.com", subject = "Hello"), mail.prefill)

        val other = assertIs<LinkTap.Sheet>(linkTap(Link.Unsupported("intent://x", null), confirm = false)).sheet
        assertFalse(other.openable)
        assertEquals("intent://x", other.url)
        assertNull(other.titleRange)
    }

    // ---- Small helpers

    @Test
    fun errorTexts() {
        assertEquals(ErrorText(R.string.message_error_sign_in, signIn = true), errorText(MailError.AuthRequired("x")))
        assertEquals(ErrorText(R.string.message_error_network), errorText(MailError.Network(IOException())))
        assertEquals(ErrorText(R.string.message_error_network), errorText(IOException("reset")))
        val server = "[NONEXISTENT] gone"
        assertEquals(ErrorText(R.string.message_error_server, server), errorText(MailError.Server(server)))
        assertEquals(ErrorText(R.string.message_error_generic), errorText(IllegalStateException()))
    }

    @Test
    fun revealBidiKeepsLineBreaks() {
        assertEquals("invoice\uFFFDfdp.exe", revealBidi("invoice\u202Efdp.exe"))
        assertEquals("a\uFFFDb\uFFFDc", revealBidi("a\u2066b\u0000c"))
        assertEquals("Date: x\r\n\tfolded", revealBidi("Date: x\r\n\tfolded"))
    }

    @Test
    fun attachmentLabels() {
        assertEquals("PDF", typeLabel("Invoice.pdf", "application/pdf"))
        assertEquals("JPEG", typeLabel("photo", "image/jpeg"))
        assertEquals("DOCX", typeLabel("a.docx", "application/octet-stream"))
        assertEquals("XML", typeLabel("feed", "application/rss+xml"))
        assertEquals("", typeLabel("blob", "application/octet-stream"))
        assertEquals(750L, decodedSize(1000, "BASE64"))
        assertEquals(1000L, decodedSize(1000, "quoted-printable"))
    }

    @Test
    fun senderDateFromRawHeaders() {
        val raw = "Received: from x\r\n by y\r\nDate: Tue, 14 Oct 2026 09:30:00\r\n +0200\r\nSubject: Hi\r\n\r\n"
        assertEquals("Tue, 14 Oct 2026 09:30:00 +0200", senderDate(raw))
        assertNull(senderDate("Subject: Hi\r\n\r\n"))
    }

    @Test
    fun contentIdsIgnoreBracketsAndCase() {
        assertTrue(sameContentId("<Logo@Example>", "logo@example"))
        assertFalse(sameContentId("logo@example", "logo2@example"))
    }
}
