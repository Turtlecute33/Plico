package app.plico.sync

import app.plico.account.MailError
import app.plico.mail.imap.ImapBadException
import app.plico.mail.imap.ImapByeException
import app.plico.mail.imap.ImapNoException
import app.plico.mail.imap.ImapProtocolException
import app.plico.mail.imap.RespCode
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpErrorsTest {
    private fun no(code: String?) = ImapNoException(code?.let { RespCode(it, emptyList()) }, "server text")

    @Test
    fun transientCodesRetry() {
        for (code in listOf("UNAVAILABLE", "INUSE", "SERVERBUG", "THROTTLED")) {
            assertEquals(Failure.TRANSIENT, OpErrors.classify(no(code)), code)
        }
        assertEquals(Failure.TRANSIENT, OpErrors.classify(ImapByeException(null, "bye")))
        assertEquals(Failure.TRANSIENT, OpErrors.classify(ImapProtocolException("broken")))
        assertEquals(Failure.TRANSIENT, OpErrors.classify(SocketTimeoutException()))
        assertEquals(Failure.TRANSIENT, OpErrors.classify(IOException("reset")))
        assertEquals(Failure.TRANSIENT, OpErrors.classify(MailError.Network(IOException())))
        assertEquals(Failure.TRANSIENT, OpErrors.classify(MailError.Throttled("slow down")))
    }

    @Test
    fun everyOtherRefusalIsPermanent() {
        // protocol PS14: OVERQUOTA, TOOBIG, CANNOT, a NO without a code and every BAD.
        for (code in listOf("OVERQUOTA", "TOOBIG", "LIMIT", "CANNOT", "NONEXISTENT", null)) {
            assertEquals(Failure.PERMANENT, OpErrors.classify(no(code)), code)
        }
        assertEquals(Failure.PERMANENT, OpErrors.classify(ImapBadException("Could not parse command")))
        assertEquals(Failure.PERMANENT, OpErrors.classify(OpRefused("too large")))
        assertEquals(Failure.PERMANENT, OpErrors.classify(MailError.Server("no")))
        assertEquals(Failure.PERMANENT, OpErrors.classify(MailError.TooLarge(10)))
        assertEquals(Failure.PERMANENT, OpErrors.classify(IllegalArgumentException("Invalid To address")))
    }

    @Test
    fun tryCreateIsItsOwnClass() {
        assertEquals(Failure.TRYCREATE, OpErrors.classify(no("TRYCREATE")))
    }

    @Test
    fun onlyConnectionErrorsEndTheCycle() {
        assertFalse(OpErrors.endsConnection(no("UNAVAILABLE")))
        assertFalse(OpErrors.endsConnection(ImapBadException("bad")))
        assertFalse(OpErrors.endsConnection(UidValidityChanged()))
        assertFalse(OpErrors.endsConnection(OpRefused("rule")))
        assertTrue(OpErrors.endsConnection(ImapByeException(null, "bye")))
        assertTrue(OpErrors.endsConnection(IOException("reset")))
        assertTrue(OpErrors.endsConnection(MailError.Network(IOException())))
    }

    @Test
    fun backoffSteps() {
        assertEquals(listOf(5_000L, 30_000L, 120_000L, 600_000L, 3_600_000L, 3_600_000L), (1..6).map(OpErrors::backoffMs))
        assertEquals(5_000L, OpErrors.backoffMs(0))
    }

    @Test
    fun textIsTheServerReply() {
        assertEquals("server text", OpErrors.text(no("OVERQUOTA")))
        assertEquals("IOException", OpErrors.text(IOException()))
    }

    @Test
    fun appendLimitIsTheSmallerOne() {
        val gmail = setOf("IMAP4REV1", "APPENDLIMIT=35651584")
        assertEquals(35_651_584L, Ops.appendLimit(gmail, 35_882_577))
        assertEquals(25_600_000L, Ops.appendLimit(setOf("IMAP4REV1"), 25_600_000))
        assertEquals(1_000L, Ops.appendLimit(setOf("APPENDLIMIT=2000"), 1_000))
        assertEquals(1_000L, Ops.appendLimit(setOf("APPENDLIMIT=junk"), 1_000))
    }
}
