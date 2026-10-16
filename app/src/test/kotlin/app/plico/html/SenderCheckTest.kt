package app.plico.html

import app.plico.R
import app.plico.data.Auth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenderCheckTest {
    private val check = SenderCheck(testPsl)

    private fun warnings(name: String, email: String, replyTo: String = "", auth: Int = Auth.UNKNOWN, first: Boolean = false) =
        check.check(name, email, replyTo, auth, first)

    @Test
    fun cleanSender() {
        assertEquals(emptyList(), warnings("Alice Example", "alice@example.com"))
        assertEquals(emptyList(), warnings("", "alice@example.com"))
        assertEquals(emptyList(), warnings("alice@example.com", "Alice@Example.com"))
        assertEquals(emptyList(), warnings("Booking.com", "noreply@mail.booking.com"))
        assertEquals(emptyList(), warnings("Example (example.co.uk)", "news@mail.example.co.uk", "help@support.example.co.uk"))
        assertEquals(emptyList(), warnings("Mr. Smith", "smith@example.com"))
    }

    @Test
    fun displayNameWithAnotherAddress() {
        assertEquals(listOf(SenderWarning.NAME_HAS_OTHER_ADDRESS), warnings("service@paypal.com", "x@evil.example"))
        assertEquals(listOf(SenderWarning.NAME_HAS_OTHER_ADDRESS), warnings("PayPal <service@paypal.com>", "x@evil.example"))
        assertEquals(listOf(SenderWarning.NAME_HAS_OTHER_ADDRESS), warnings("alice@example.com", "bob@example.com"))
    }

    @Test
    fun displayNameWithAnotherDomain() {
        assertEquals(listOf(SenderWarning.NAME_HAS_OTHER_DOMAIN), warnings("PayPal.com Support", "x@evil.example"))
        assertEquals(listOf(SenderWarning.NAME_HAS_OTHER_DOMAIN), warnings("www.bank.co.uk", "alerts@other.co.uk"))
        assertEquals(listOf(SenderWarning.NAME_HAS_OTHER_DOMAIN), warnings("good.github.io", "x@evil.github.io"))
        assertEquals(emptyList(), warnings("Version 2.0", "x@example.com"))
    }

    @Test
    fun replyToDomain() {
        assertEquals(listOf(SenderWarning.REPLY_TO_OTHER_DOMAIN), warnings("Bank", "alerts@bank.example", "Bank <help@evil.example>"))
        assertEquals(listOf(SenderWarning.REPLY_TO_OTHER_DOMAIN), warnings("Bank", "a@bank.co.uk", "b@other.co.uk"))
        assertEquals(listOf(SenderWarning.REPLY_TO_OTHER_DOMAIN), warnings("A", "a@example.com", "a@example.com, b@evil.example"))
        assertEquals(emptyList(), warnings("Bank", "alerts@news.bank.example", "help@bank.example"))
        assertEquals(emptyList(), warnings("Bank", "alerts@bank.example", "not an address"))
    }

    @Test
    fun authAndFirstContact() {
        assertEquals(listOf(SenderWarning.AUTH_FAILED), warnings("A", "a@example.com", auth = Auth.FAIL))
        assertEquals(emptyList(), warnings("A", "a@example.com", auth = Auth.PASS))
        assertEquals(listOf(SenderWarning.FIRST_CONTACT), warnings("A", "a@example.com", first = true))
        assertEquals(
            listOf(
                SenderWarning.AUTH_FAILED, SenderWarning.NAME_HAS_OTHER_ADDRESS, SenderWarning.REPLY_TO_OTHER_DOMAIN,
                SenderWarning.FIRST_CONTACT,
            ),
            warnings("ceo@company.example", "x@evil.example", "y@other.example", Auth.FAIL, true),
        )
    }

    @Test
    fun trustNeedsGmailDmarcPassOutsideJunk() {
        assertEquals(SenderTrust.VERIFIED, SenderCheck.trust(gmail = true, auth = Auth.PASS, inJunk = false))
        assertEquals(SenderTrust.NONE, SenderCheck.trust(gmail = true, auth = Auth.UNKNOWN, inJunk = false))
        assertEquals(SenderTrust.NONE, SenderCheck.trust(gmail = true, auth = Auth.FAIL, inJunk = false))
        assertEquals(SenderTrust.NONE, SenderCheck.trust(gmail = true, auth = Auth.PASS, inJunk = true))
        assertTrue(SenderTrust.VERIFIED.alwaysShowRemote && SenderTrust.VERIFIED.oneClickUnsubscribe)
        assertFalse(SenderTrust.NONE.alwaysShowRemote || SenderTrust.NONE.oneClickUnsubscribe)
        assertNull(SenderTrust.VERIFIED.note)
    }

    @Test
    fun riseupTrustIsFromOnly() {
        assertEquals(SenderTrust.FROM_ONLY, SenderCheck.trust(gmail = false, auth = Auth.UNKNOWN, inJunk = false))
        assertEquals(SenderTrust.NONE, SenderCheck.trust(gmail = false, auth = Auth.UNKNOWN, inJunk = true))
        assertTrue(SenderTrust.FROM_ONLY.alwaysShowRemote)
        assertFalse(SenderTrust.FROM_ONLY.oneClickUnsubscribe)
        assertEquals(R.string.html_sender_trust_fakeable, SenderTrust.FROM_ONLY.note)
    }
}
