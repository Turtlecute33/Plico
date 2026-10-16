package app.plico.ui.compose

import app.plico.account.Providers
import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Identity
import app.plico.data.Provider
import app.plico.data.PushMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftRulesTest {
    @Test
    fun encodedSizeIsRawTimes137RoundedUp() {
        assertEquals(0, DraftRules.encodedSize(0))
        assertEquals(2, DraftRules.encodedSize(1))
        assertEquals(137, DraftRules.encodedSize(100))
        assertEquals(1_370_000, DraftRules.encodedSize(1_000_000))
    }

    @Test
    fun sizeLimitBoundaryForRiseup() {
        val limit = Providers.RISEUP.maxMessageBytes
        // 18,686,131 x 1.37 = 25,599,999.47 and 18,686,132 x 1.37 = 25,600,000.84
        assertFalse(DraftRules.encodedSize(18_686_131) > limit)
        assertTrue(DraftRules.encodedSize(18_686_132) > limit)
    }

    @Test
    fun sizeLimitBoundaryForGmail() {
        val limit = Providers.GMAIL.maxMessageBytes
        assertFalse(DraftRules.encodedSize(limit * 100 / 137) > limit)
        assertTrue(DraftRules.encodedSize(limit * 100 / 137 + 1) > limit)
    }

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
        assertEquals(DraftRules.Leave.DISCARD, DraftRules.leave(edited = false, empty = true))
        assertEquals(DraftRules.Leave.DISCARD, DraftRules.leave(edited = true, empty = true))
        assertEquals(DraftRules.Leave.SAVE, DraftRules.leave(edited = true, empty = false))
        // A shared draft that the user did not touch stays local (DESIGN §7.6).
        assertEquals(DraftRules.Leave.KEEP, DraftRules.leave(edited = false, empty = false))
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
        stateText = null, caps = null, serverKeepaliveS = null, quotaUsed = null, quotaLimit = null, sort = id.toInt(),
    )
}
