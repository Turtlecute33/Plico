package app.plico.ui.compose

import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.DraftState
import app.plico.data.Identity
import app.plico.data.Provider
import app.plico.data.PushMode
import app.plico.sync.ReplyRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftRulesTest {
    @Test
    fun utf8Length() {
        assertEquals(0, DraftRules.utf8Length(""))
        for (s in listOf("hello", "Müller", "日本語", "a😀b")) {
            assertEquals(s.toByteArray(Charsets.UTF_8).size.toLong(), DraftRules.utf8Length(s), s)
        }
    }

    @Test
    fun blankBodyIgnoresTheSignature() {
        assertTrue(DraftRules.isBlankBody(""))
        assertTrue(DraftRules.isBlankBody("\n\n  \n"))
        assertTrue(DraftRules.isBlankBody("\n\n-- \nJane\nRiseup"))
        assertTrue(DraftRules.isBlankBody("-- \nJane"))
        assertFalse(DraftRules.isBlankBody("Hi\n\n-- \nJane"))
        assertFalse(DraftRules.isBlankBody("--\nnot a signature separator"))
    }

    @Test
    fun leaveRules() {
        assertEquals(DraftRules.Leave.DISCARD, DraftRules.leave(edited = false, empty = true, fresh = false))
        assertEquals(DraftRules.Leave.DISCARD, DraftRules.leave(edited = true, empty = true, fresh = false))
        assertEquals(DraftRules.Leave.SAVE, DraftRules.leave(edited = true, empty = false, fresh = false))
        assertEquals(DraftRules.Leave.SAVE, DraftRules.leave(edited = true, empty = false, fresh = true))
        // A shared draft, or one opened from the Drafts view, that the user did not touch stays (DESIGN §7.6).
        assertEquals(DraftRules.Leave.KEEP, DraftRules.leave(edited = false, empty = false, fresh = false))
        // A Reply or Forward left without an edit goes, with its copied files: no stray drafts.
        assertEquals(DraftRules.Leave.DISCARD, DraftRules.leave(edited = false, empty = false, fresh = true))
    }

    @Test
    fun aQueuedDraftOpensReadOnlyAndKeepsItsSend() {
        assertEquals(ComposeViewModel.Phase.EDITING, DraftRules.openPhase(DraftState.EDITING))
        assertEquals(ComposeViewModel.Phase.EDITING, DraftRules.openPhase(DraftState.FAILED))
        assertEquals(ComposeViewModel.Phase.EDITING, DraftRules.openPhase(DraftState.UNCERTAIN))
        assertEquals(ComposeViewModel.Phase.WAITING, DraftRules.openPhase(DraftState.QUEUED))
        assertEquals(ComposeViewModel.Phase.GONE, DraftRules.openPhase(DraftState.SENDING))
        assertEquals(ComposeViewModel.Phase.GONE, DraftRules.openPhase(DraftState.SENT))
    }

    @Test
    fun sendAsksForABlankSubjectThenAForgottenAttachment() {
        val none = emptyList<SendQuestion>()
        assertEquals(none, DraftRules.sendQuestions("Hello", "Hi there", hasAttachments = false))
        assertEquals(listOf(SendQuestion.NO_SUBJECT), DraftRules.sendQuestions(" ", "Hi there", hasAttachments = false))
        assertEquals(
            listOf(SendQuestion.NO_SUBJECT, SendQuestion.NO_ATTACHMENT),
            DraftRules.sendQuestions("", "The plan is attached.", hasAttachments = false),
        )
        for (text in listOf("I attach the plan", "See the ATTACHMENT", "two attachments")) {
            assertEquals(listOf(SendQuestion.NO_ATTACHMENT), DraftRules.sendQuestions("Plan", text, false), text)
        }
        assertEquals(none, DraftRules.sendQuestions("Plan", "The plan is attached.", hasAttachments = true))
        // Only the whole word counts.
        assertEquals(none, DraftRules.sendQuestions("Plan", "I am attaching nothing; attachable", false))
    }

    @Test
    fun theAttachmentCheckReadsOnlyTheNewText() {
        val reply = "Thanks!" + ReplyRules.replyBody(
            signature = "Jane",
            attribution = ReplyRules.attribution(0, "Alice"),
            text = "The file is attached.",
        )
        assertEquals(emptyList(), DraftRules.sendQuestions("Re: Plan", reply, hasAttachments = false))
        val noSignature = "Thanks!" + ReplyRules.replyBody("", ReplyRules.attribution(0, "Alice"), "See attachment")
        assertEquals(emptyList(), DraftRules.sendQuestions("Re: Plan", noSignature, hasAttachments = false))
        val forward = "FYI" + ReplyRules.forwardBody("", null, 0, "Plan", emptyList(), emptyList(), "Attached: plan")
        assertEquals(emptyList(), DraftRules.sendQuestions("Fwd: Plan", forward, hasAttachments = false))
        assertEquals(emptyList(), DraftRules.sendQuestions("Plan", "Hi\n\n-- \nJane, attach files here", false))
        assertEquals(
            listOf(SendQuestion.NO_ATTACHMENT),
            DraftRules.sendQuestions("Re: Plan", "Here is the attachment." + reply.removePrefix("Thanks!"), false),
        )
    }

    @Test
    fun senderOptionsListAccountsThenIdentities() {
        val riseup = account(1, Provider.RISEUP, "jane@riseup.net", "Jane", "Riseup")
        val gmail = account(2, Provider.GMAIL, "jane@gmail.com", "", "Gmail")
        val identities = mapOf(
            1L to listOf(
                Identity(10, 1, "alias@riseup.net", ""),
                Identity(11, 1, "JANE@riseup.net", "Duplicate"),
                Identity(12, 1, "work@riseup.net", "Jane Work"),
            ),
        )
        val options = DraftRules.senderOptions(listOf(riseup, gmail)) { identities[it].orEmpty() }
        assertEquals(
            listOf(
                SenderOption(1, Provider.RISEUP, "Riseup", "jane@riseup.net", "Jane"),
                SenderOption(1, Provider.RISEUP, "Riseup", "alias@riseup.net", "Jane"),
                SenderOption(1, Provider.RISEUP, "Riseup", "work@riseup.net", "Jane Work"),
                SenderOption(2, Provider.GMAIL, "Gmail", "jane@gmail.com", ""),
            ),
            options,
        )
        assertEquals("Jane <jane@riseup.net>", options[0].display)
        assertEquals("jane@gmail.com", options[3].display)
    }

    @Test
    fun pickSender() {
        val riseup = account(1, Provider.RISEUP, "jane@riseup.net", "Jane", "Riseup")
        val gmail = account(2, Provider.GMAIL, "jane@gmail.com", "", "Gmail")
        val options = DraftRules.senderOptions(listOf(riseup, gmail)) {
            if (it == 1L) listOf(Identity(10, 1, "alias@riseup.net", "")) else emptyList()
        }
        assertEquals(options[1], DraftRules.pickSender(options, 1, "Alias@Riseup.net"))
        assertEquals(options[2], DraftRules.pickSender(options, 2, "jane@gmail.com"))
        assertEquals(options[0], DraftRules.pickSender(options, 1, ""))
        // An identity that was removed stays the sender until the user picks another one.
        assertEquals(
            SenderOption(1, Provider.RISEUP, "Riseup", "old@riseup.net", ""),
            DraftRules.pickSender(options, 1, "old@riseup.net"),
        )
        assertNull(DraftRules.pickSender(options, 3, "jane@riseup.net"))
    }

    private fun account(id: Long, provider: Provider, email: String, name: String, label: String) = Account(
        id = id, provider = provider, email = email, senderName = name, label = label, color = 0,
        auth = AuthKind.PASSWORD, deviceAccount = null, bodyDays = 30, pushMode = PushMode.INSTANT, pollMinutes = 15,
        gmailCategories = setOf("primary"), archiveFolder = null, signature = "", state = AccountState.OK,
        stateText = null, serverKeepaliveS = null, quotaUsed = null, quotaLimit = null, sort = id.toInt(),
    )
}
