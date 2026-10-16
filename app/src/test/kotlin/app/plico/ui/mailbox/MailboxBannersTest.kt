package app.plico.ui.mailbox

import app.plico.data.AccountState
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.Provider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailboxBannersTest {
    @Test
    fun pushProblemsInOrderOfImportance() {
        assertNull(MailboxBanners.pushProblem(null))
        assertNull(MailboxBanners.pushProblem(pushStatus()))
        assertEquals(PushProblem.PAUSED, MailboxBanners.pushProblem(pushStatus(paused = true, restricted = true)))
        assertEquals(PushProblem.STOPPED, MailboxBanners.pushProblem(pushStatus(stopped = true, restricted = true)))
        assertEquals(PushProblem.RESTRICTED, MailboxBanners.pushProblem(pushStatus(restricted = true, battery = false)))
        // Notifications off is a Settings matter, not a mailbox banner.
        assertNull(MailboxBanners.pushProblem(pushStatus(notifications = false)))
        assertEquals(PushProblem.LATE, MailboxBanners.pushProblem(pushStatus(battery = false, exact = false)))
        // One of the two grants is enough for exact alarms.
        assertNull(MailboxBanners.pushProblem(pushStatus(battery = false, exact = true)))
        assertNull(MailboxBanners.pushProblem(pushStatus(battery = true, exact = false)))
    }

    @Test
    fun accountProblemsFirstThenGuardPushAndNotes() {
        val signIn = account(1, state = AccountState.SIGN_IN_NEEDED)
        val note = account(2, Provider.GMAIL, stateText = "Trash is hidden")
        val guarded = folder(5, 1, "INBOX", FolderRole.INBOX, guard = true)
        val banners = MailboxBanners.of(
            shown = listOf(signIn, note),
            all = listOf(signIn, note),
            guarded = listOf(guarded),
            status = pushStatus(paused = true),
            gmailIpNoteShown = false,
        )
        assertEquals(
            listOf(
                MailboxBanner.SignIn(signIn),
                MailboxBanner.Guard(guarded),
                MailboxBanner.Push(PushProblem.PAUSED),
                MailboxBanner.Note(note),
                MailboxBanner.GmailIp,
            ),
            banners,
        )
    }

    @Test
    fun failedSendsOfShownAccountsGetABanner() {
        val riseup = account(1)
        val gmail = account(2, Provider.GMAIL)
        val banners = MailboxBanners.of(listOf(riseup), listOf(riseup, gmail), emptyList(), null, true, mapOf(1L to 2, 2L to 1))
        assertEquals(listOf<MailboxBanner>(MailboxBanner.Unsent(riseup, 2)), banners)
        assertEquals(emptyList(), MailboxBanners.of(listOf(riseup), listOf(riseup), emptyList(), null, true, mapOf(1L to 0)))
    }

    @Test
    fun accountStatesMapToTheirBanners() {
        val tls = account(1, state = AccountState.TLS_PROBLEM)
        val setup = account(2, state = AccountState.SETUP_PROBLEM, stateText = "Show All Mail in IMAP")
        val banners = MailboxBanners.of(listOf(tls, setup), listOf(tls, setup), emptyList(), null, true)
        assertEquals(listOf(MailboxBanner.Tls(tls), MailboxBanner.Setup(setup)), banners)
    }

    @Test
    fun onlyShownAccountsButPushAndIpNoteForAll() {
        val other = account(2, Provider.GMAIL, state = AccountState.SIGN_IN_NEEDED)
        val shown = account(1)
        val banners = MailboxBanners.of(listOf(shown), listOf(shown, other), emptyList(), pushStatus(stopped = true), false)
        assertEquals(listOf(MailboxBanner.Push(PushProblem.STOPPED), MailboxBanner.GmailIp), banners)
    }

    @Test
    fun noPushOrIpBannerWithoutAccounts() {
        assertTrue(MailboxBanners.of(emptyList(), emptyList(), emptyList(), pushStatus(paused = true), false).isEmpty())
        assertTrue(MailboxBanners.of(listOf(account(1)), listOf(account(1)), emptyList(), null, false).isEmpty())
    }

    @Test
    fun guardedFoldersOfAView() {
        val inbox = folder(1, 1, "INBOX", FolderRole.INBOX, guard = true)
        val lists = folder(2, 1, "lists", guard = true)
        val allMail = folder(3, 2, "[Gmail]/All Mail", FolderRole.ALL, guard = true)
        val label = folder(4, 2, "Work", kind = FolderKind.LABEL)
        val other = folder(5, 1, "Bills")
        val folders = listOf(inbox, lists, allMail, label, other)
        assertEquals(listOf(inbox, lists, allMail), MailboxBanners.guarded(null, folders))
        assertEquals(listOf(inbox), MailboxBanners.guarded(inbox, folders))
        assertEquals(emptyList(), MailboxBanners.guarded(other, folders))
        // A Gmail label view is frozen with All Mail behind it.
        assertEquals(listOf(allMail), MailboxBanners.guarded(label, folders))
    }
}
