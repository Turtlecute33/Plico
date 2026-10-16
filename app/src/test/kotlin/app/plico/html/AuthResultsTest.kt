package app.plico.html

import app.plico.data.Auth
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthResultsTest {
    private fun parse(header: String?) = AuthResults.parse(header)

    @Test
    fun gmailPass() {
        val header = "mx.google.com;\r\n       dkim=pass header.i=@example.com header.s=s1 header.b=AbC123;\r\n" +
            "       spf=pass (google.com: domain of bounce@example.com designates 192.0.2.1 as permitted sender)\r\n" +
            "       smtp.mailfrom=bounce@example.com;\r\n" +
            "       dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=example.com"
        assertEquals(Auth.PASS, parse(header))
        assertEquals(Auth.PASS, parse("MX.GOOGLE.COM; DMARC=PASS header.from=example.com"))
        assertEquals(Auth.PASS, parse("mx.google.com 1; dmarc = pass header.from=example.com"))
        assertEquals(
            Auth.PASS,
            parse("mx.google.com; arc=pass (i=1 spf=pass dkim=pass dmarc=pass fromdomain=x.com); dmarc=pass header.from=x.com"),
        )
    }

    @Test
    fun gmailFail() {
        assertEquals(
            Auth.FAIL,
            parse(
                "mx.google.com; dkim=pass header.i=@evil.example; spf=pass smtp.mailfrom=evil.example; " +
                    "dmarc=fail (p=QUARANTINE) header.from=bank.example",
            ),
        )
        assertEquals(Auth.FAIL, parse("mx.google.com; dkim=fail header.i=@example.com; spf=fail smtp.mailfrom=example.com"))
        assertEquals(
            Auth.FAIL,
            parse("mx.google.com; spf=fail smtp.mailfrom=a.example; dkim=fail header.i=@a.example; dkim=fail header.i=@b.example"),
        )
    }

    @Test
    fun unknownCases() {
        assertEquals(Auth.UNKNOWN, parse(null))
        assertEquals(Auth.UNKNOWN, parse(""))
        assertEquals(Auth.UNKNOWN, parse("mx.google.com; spf=softfail smtp.mailfrom=x.example; dkim=none"))
        assertEquals(Auth.UNKNOWN, parse("mx.google.com; spf=fail smtp.mailfrom=x.example; dkim=pass header.i=@x.example"))
        assertEquals(
            Auth.UNKNOWN,
            parse("mx.google.com; spf=fail smtp.mailfrom=x.example; dkim=fail header.i=@x.example; dkim=pass header.i=@y.example"),
        )
        assertEquals(Auth.UNKNOWN, parse("mx.google.com; spf=fail smtp.mailfrom=x.example"))
        assertEquals(Auth.UNKNOWN, parse("mx.google.com; dmarc=none header.from=x.example"))
        assertEquals(Auth.UNKNOWN, parse("mx.google.com; dmarc=temperror"))
        assertEquals(Auth.UNKNOWN, parse("mx.google.com; none"))
    }

    @Test
    fun otherAuthservIdsAreIgnored() {
        assertEquals(Auth.UNKNOWN, parse("mx.riseup.net; dmarc=pass header.from=example.com"))
        assertEquals(Auth.UNKNOWN, parse("mx.google.com.evil.example; dmarc=pass header.from=example.com"))
        assertEquals(Auth.UNKNOWN, parse("evil.example; dmarc=fail"))
        assertEquals(Auth.UNKNOWN, parse("dmarc=pass; mx.google.com"))
    }

    @Test
    fun commentsAndQuotesCannotFakeResults() {
        assertEquals(Auth.UNKNOWN, parse("mx.google.com; spf=none (; dmarc=pass) smtp.mailfrom=x.example"))
        assertEquals(Auth.UNKNOWN, parse("mx.google.com; spf=none smtp.mailfrom=\"x;dmarc=pass\"@evil.example"))
        assertEquals(Auth.UNKNOWN, parse("mx.google.com; spf=none (nested (; dmarc=pass) still) smtp.mailfrom=x"))
        assertEquals(Auth.UNKNOWN, parse("mx.google.com; spf=none smtp.mailfrom=\"a\\\";dmarc=pass\"@evil.example"))
        // The first dmarc result counts.
        assertEquals(Auth.FAIL, parse("mx.google.com; dmarc=fail header.from=bank.example; dmarc=pass header.from=evil.example"))
        // A comment before the authserv-id.
        assertEquals(Auth.PASS, parse("(received by Google) mx.google.com; dmarc=pass"))
    }
}
