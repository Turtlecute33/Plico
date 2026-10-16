package app.plico.mail.imap

import app.plico.mail.sasl.Sasl
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImapConnectionTest {

    private val fake = FakeStream()
    private val logs = ArrayList<String>()
    private val conn = ImapConnection(fake) { logs += it }

    private fun connect(greeting: String = "* OK [CAPABILITY IMAP4rev1 LITERAL+ IDLE] Dovecot ready.") {
        fake.server(greeting)
        conn.greeting()
    }

    @Test
    fun dovecotGreetingSetsCapabilities() {
        connect("* OK [CAPABILITY IMAP4rev1 SASL-IR LOGIN-REFERRALS ID ENABLE IDLE LITERAL+ AUTH=PLAIN AUTH=LOGIN] Dovecot ready.")
        assertEquals(
            setOf("IMAP4REV1", "SASL-IR", "LOGIN-REFERRALS", "ID", "ENABLE", "IDLE", "LITERAL+", "AUTH=PLAIN", "AUTH=LOGIN"),
            conn.capabilities,
        )
        assertEquals(listOf("<<< * OK [CAPABILITY]"), logs)
        fake.finish()
    }

    @Test
    fun gmailGreetingHasNoCapabilities() {
        fake.server("* OK Gimap ready for requests from 1.2.3.4 abc")
        val greeting = conn.greeting()
        assertEquals("Gimap ready for requests from 1.2.3.4 abc", greeting.text)
        assertEquals(emptySet(), conn.capabilities)
        fake.finish()
    }

    @Test
    fun byeGreetingThrows() {
        fake.server("* BYE Too many connections")
        val e = assertFailsWith<ImapByeException> { conn.greeting() }
        assertEquals("Too many connections", e.message)
    }

    @Test
    fun unexpectedGreetingThrows() {
        fake.server("A1 OK what")
        assertFailsWith<ImapProtocolException> { conn.greeting() }
    }

    @Test
    fun tagsCountUp() {
        connect()
        fake.client("A1 NOOP").server("A1 OK NOOP completed.")
        fake.client("A2 NOOP").server("A2 OK NOOP completed.")
        assertEquals(Tagged("A1", "OK", null, "NOOP completed."), conn.execute(Command.raw("NOOP")))
        assertEquals("A2", conn.execute(Command.raw("NOOP")).tag)
        fake.finish()
    }

    @Test
    fun stringsAreQuotedWhenSafe() {
        connect("* OK [CAPABILITY IMAP4rev1] ready")
        fake.client("A1 SELECT \"[Gmail]/All Mail\"").server("A1 OK done")
        fake.client("A2 X \"\" \"a b\"").server("A2 OK done")
        conn.execute(Command.build { raw("SELECT"); string("[Gmail]/All Mail") })
        conn.execute(Command.build { raw("X"); string(""); string("a b") })
        fake.finish()
    }

    @Test
    fun synchronizingLiteralWaitsForContinuation() {
        connect("* OK [CAPABILITY IMAP4rev1] ready")
        val untagged = ArrayList<Untagged>()
        val unsolicited = ArrayList<Untagged>()
        conn.onUnsolicited = { unsolicited += it }
        fake.client("A1 RENAME {3}")
            .server("* 3 EXISTS", "+ Ready for literal data")
            .client("a\"b {4}")
            .server("+ go")
            .client("c\\de")
            .server("A1 OK done")
        conn.execute(Command.build { raw("RENAME"); string("a\"b"); string("c\\de") }) { untagged += it }
        assertEquals(listOf(Untagged(3, "EXISTS", emptyList(), null, null)), untagged)
        assertEquals(untagged, unsolicited)
        fake.finish()
    }

    @Test
    fun taggedNoInsteadOfContinuationEndsTheCommand() {
        connect("* OK [CAPABILITY IMAP4rev1 LITERAL-] ready")
        val message = "x".repeat(5000)
        fake.client("A1 APPEND \"INBOX\" {5000}").server("A1 NO [TOOBIG] Message too large")
        val e = assertFailsWith<ImapNoException> {
            conn.execute(Command.build { raw("APPEND"); string("INBOX"); bytes(message.toByteArray()) })
        }
        assertEquals("TOOBIG", e.code?.name)
        assertEquals("Message too large", e.message)
        fake.client("A2 NOOP").server("A2 OK done")
        conn.execute(Command.raw("NOOP"))
        fake.finish()
        assertEquals(
            listOf("<<< * OK [CAPABILITY]", ">>> A1 APPEND", "<<< A1 NO [TOOBIG]", ">>> A2 NOOP", "<<< A2 OK"),
            logs,
        )
    }

    @Test
    fun literalPlusIsNonSynchronizing() {
        connect("* OK [CAPABILITY IMAP4rev1 LITERAL+] ready")
        val big = "y".repeat(5000)
        fake.client("A1 APPEND \"Sent\" (\\Seen) {5000+}\r\n$big").server("A1 OK [APPENDUID 1 2] Append completed.")
        conn.execute(Command.build { raw("APPEND"); string("Sent"); raw("(\\Seen)"); bytes(big.toByteArray()) })
        fake.finish()
    }

    @Test
    fun literalMinusIsNonSynchronizingUpTo4096Bytes() {
        connect("* OK [CAPABILITY IMAP4rev1 LITERAL-] ready")
        val small = "z".repeat(4096)
        fake.client("A1 X {4096+}\r\n$small").server("A1 OK done")
        conn.execute(Command.build { raw("X"); bytes(small.toByteArray()) })
        fake.client("A2 X {4097}").server("+ ok").client("${small}z").server("A2 OK done")
        conn.execute(Command.build { raw("X"); bytes((small + "z").toByteArray()) })
        fake.finish()
    }

    @Test
    fun nonAsciiTextIsAUtf8Literal() {
        connect("* OK [CAPABILITY IMAP4rev1 LITERAL-] ready")
        fake.client("A1 UID SEARCH CHARSET UTF-8 X-GM-RAW {9+}\r\nEntwürfe").server("A1 OK SEARCH completed (Success)")
        conn.execute(Command.build { raw("UID SEARCH CHARSET UTF-8 X-GM-RAW"); string("Entwürfe") })
        fake.finish()
    }

    @Test
    fun bytesAreAlwaysALiteralAndGroupsNest() {
        connect("* OK [CAPABILITY IMAP4rev1 LITERAL+] ready")
        fake.client("A1 UID STORE 7 +X-GM-LABELS.SILENT (\\Inbox \"Work\" {3+}\r\na\"b {2+}\r\nok)")
            .server("A1 OK done")
        conn.execute(
            Command.build {
                raw("UID STORE 7 +X-GM-LABELS.SILENT")
                list { raw("\\Inbox"); string("Work"); string("a\"b"); bytes("ok".toByteArray()) }
            },
        )
        fake.finish()
    }

    @Test
    fun continuationCallbackAnswersChallenges() {
        connect()
        val ir = Sasl.xoauth2("someone@gmail.com", "ya29.token")
        fake.client("A1 AUTHENTICATE XOAUTH2 $ir")
            .server("+ eyJzdGF0dXMiOiI0MDAiLCJzY2hlbWVzIjoiQmVhcmVyIiwic2NvcGUiOiJodHRwczovL21haWwuZ29vZ2xlLmNvbS8ifQ==")
            .client("")
            .server("A1 NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)")
        val challenges = ArrayList<String>()
        val e = assertFailsWith<ImapNoException> {
            conn.execute(Command.raw("AUTHENTICATE", "XOAUTH2", ir), {}) { challenges += it; "" }
        }
        assertEquals("AUTHENTICATIONFAILED", e.code?.name)
        assertEquals(1, challenges.size)
        assertEquals(listOf("<<< * OK [CAPABILITY]", ">>> A1 AUTHENTICATE", "<<< A1 NO [AUTHENTICATIONFAILED]"), logs)
        assertTrue(logs.none { "ya29" in it || ir in it || "someone" in it })
        fake.finish()
    }

    @Test
    fun unexpectedContinuationIsAProtocolError() {
        connect()
        fake.client("A1 NOOP").server("+ what")
        assertFailsWith<ImapProtocolException> { conn.execute(Command.raw("NOOP")) }
    }

    @Test
    fun badAndWrongTag() {
        connect()
        fake.client("A1 FOO").server("A1 BAD Error in IMAP command FOO: Unknown command.")
        val bad = assertFailsWith<ImapBadException> { conn.execute(Command.raw("FOO")) }
        assertEquals("Error in IMAP command FOO: Unknown command.", bad.message)
        fake.client("A2 NOOP").server("A7 OK done")
        assertFailsWith<ImapProtocolException> { conn.execute(Command.raw("NOOP")) }
        assertTrue(fake.closed)
    }

    @Test
    fun callbackErrorInsideAResponseClosesTheConnection() {
        connect()
        fake.client("A1 UID FETCH 1:* (FLAGS)").server(
            "* 1 FETCH (UID 4 FLAGS (\\Seen))",
            "* 2 FETCH (UID 9 FLAGS (\\Seen))",
            "A1 OK Fetch completed.",
        )
        val e = assertFailsWith<IllegalStateException> {
            conn.execute(Command.raw("UID FETCH", "1:*", "(FLAGS)")) { error("database is locked") }
        }
        assertEquals("database is locked", e.message)
        assertTrue(fake.closed)
        assertFalse(conn.isOpen)
        // The rest of A1's responses never reach a later command.
        val unsolicited = ArrayList<Untagged>()
        conn.onUnsolicited = { unsolicited += it }
        val next = assertFailsWith<IOException> { conn.execute(Command.raw("NOOP")) }
        assertEquals("Connection closed", next.message)
        assertEquals(emptyList(), unsolicited)
        assertEquals(listOf("<<< * OK [CAPABILITY]", ">>> A1 UID FETCH"), logs)
    }

    @Test
    fun readerErrorInsideAResponseClosesTheConnection() {
        connect()
        fake.client("A1 NOOP").server("* 1 FETCH (BODY[] {x}", "* 2 FETCH (UID 9 FLAGS (\\Seen))", "A1 OK done")
        assertFailsWith<ImapProtocolException> { conn.execute(Command.raw("NOOP")) }
        assertTrue(fake.closed)
        assertFailsWith<IOException> { conn.idle(object : IdleHooks {}) {} }
        assertFailsWith<IOException> { conn.execute(Command.raw("NOOP")) }
    }

    @Test
    fun byeDuringCommandThrows() {
        connect()
        fake.client("A1 NOOP").server("* BYE Session expired, please login again.")
        val e = assertFailsWith<ImapByeException> { conn.execute(Command.raw("NOOP")) }
        assertEquals("Session expired, please login again.", e.message)
        fake.finish()
    }

    @Test
    fun byeIsExpectedForLogout() {
        connect()
        val untagged = ArrayList<String>()
        fake.client("A1 LOGOUT").server("* BYE Logging out", "A1 OK Logout completed (0.001 + 0.000 secs).")
        conn.execute(Command.raw("LOGOUT")) { untagged += it.keyword }
        assertEquals(listOf("BYE"), untagged)
        fake.finish()
    }

    @Test
    fun capabilitiesFollowTheServer() {
        connect()
        fake.client("A1 CAPABILITY").server("* CAPABILITY IMAP4rev1 IDLE MOVE", "A1 OK Capability completed.")
        conn.execute(Command.raw("CAPABILITY"))
        assertEquals(setOf("IMAP4REV1", "IDLE", "MOVE"), conn.capabilities)
        fake.client("A2 X").server("A2 OK [CAPABILITY IMAP4rev1 X-GM-EXT-1] done")
        conn.execute(Command.raw("X"))
        assertEquals(setOf("IMAP4REV1", "X-GM-EXT-1"), conn.capabilities)
        fake.client("A3 Y").server("* OK [CAPABILITY imap4rev1 compress=deflate] now", "A3 OK done")
        conn.execute(Command.raw("Y"))
        assertEquals(setOf("IMAP4REV1", "COMPRESS=DEFLATE"), conn.capabilities)
        fake.finish()
    }

    @Test
    fun changesGoToOnUnsolicited() {
        connect()
        val unsolicited = ArrayList<String>()
        val all = ArrayList<String>()
        conn.onUnsolicited = { unsolicited += it.keyword }
        fake.client("A1 NOOP").server(
            "* 5 EXISTS", "* 2 EXPUNGE", "* 1 FETCH (FLAGS (\\Seen))", "* 0 RECENT", "* FLAGS (\\Seen)", "* OK [ALERT] hi",
            "A1 OK NOOP completed.",
        )
        conn.execute(Command.raw("NOOP")) { all += it.keyword }
        assertEquals(listOf("EXISTS", "EXPUNGE", "FETCH", "RECENT"), unsolicited)
        assertEquals(listOf("EXISTS", "EXPUNGE", "FETCH", "RECENT", "FLAGS", "OK"), all)
        fake.finish()
    }

    @Test
    fun logsOnlyTagsVerbsAndCodes() {
        connect()
        val ir = Sasl.plain("alice@riseup.net", "s3cret pass")
        fake.client("A1 AUTHENTICATE PLAIN $ir").server("A1 OK [CAPABILITY IMAP4rev1 IDLE] Logged in")
        fake.client("A2 SELECT \"Privat\"").server("A2 OK [READ-WRITE] Select completed.")
        fake.client("A3 UID FETCH 1:* (FLAGS)").server("A3 OK Fetch completed.")
        fake.client("A4 UID SEARCH TEXT \"secret words\"").server("A4 OK Search completed.")
        conn.execute(Command.raw("AUTHENTICATE", "PLAIN", ir))
        conn.execute(Command.build { raw("SELECT"); string("Privat") })
        conn.execute(Command.raw("UID FETCH", "1:*", "(FLAGS)"))
        conn.execute(Command.build { raw("UID"); raw("SEARCH"); raw("TEXT"); string("secret words") })
        assertEquals(
            listOf(
                "<<< * OK [CAPABILITY]",
                ">>> A1 AUTHENTICATE", "<<< A1 OK [CAPABILITY]",
                ">>> A2 SELECT", "<<< A2 OK [READ-WRITE]",
                ">>> A3 UID FETCH", "<<< A3 OK",
                ">>> A4 UID SEARCH", "<<< A4 OK",
            ),
            logs,
        )
        fake.finish()
    }

    @Test
    fun compressionSwitchesToANewReader() {
        connect("* OK [CAPABILITY IMAP4rev1 COMPRESS=DEFLATE] ready")
        val oldInput = fake.input
        fake.client("A1 COMPRESS DEFLATE").server("A1 OK Begin compression.")
        fake.client("A2 NOOP").server("A2 OK done")
        conn.startCompression()
        assertEquals(1, fake.compressions)
        assertTrue(oldInput !== fake.input)
        conn.execute(Command.raw("NOOP"))
        fake.finish()
    }

    @Test
    fun refusedCompressionKeepsTheStream() {
        connect()
        fake.client("A1 COMPRESS DEFLATE").server("A1 NO [COMPRESSIONACTIVE] Already compressing")
        assertFailsWith<ImapNoException> { conn.startCompression() }
        assertEquals(0, fake.compressions)
        fake.finish()
    }

    @Test
    fun closeIsIdempotentAndWakesABlockedRead() {
        connect()
        fake.client("A1 NOOP", "never sent")
        val started = CountDownLatch(1)
        var error: Throwable? = null
        val worker = thread {
            started.countDown()
            error = runCatching { conn.execute(Command.raw("NOOP")) }.exceptionOrNull()
        }
        started.await()
        Thread.sleep(100)
        conn.close()
        conn.close()
        worker.join(TimeUnit.SECONDS.toMillis(5))
        assertIs<IOException>(error)
        assertTrue(fake.closed)
    }
}
