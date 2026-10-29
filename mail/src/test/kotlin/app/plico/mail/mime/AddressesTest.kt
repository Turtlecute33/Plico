package app.plico.mail.mime

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddressesTest {
    private fun parse(value: String) = Addresses.parseList(value)

    @Test
    fun isValidAddrSpecIsStrict() {
        assertTrue(Addresses.isValidAddrSpec("jdoe@machine.example"))
        assertFalse(Addresses.isValidAddrSpec("jdoe@machine.example\r\nBcc: x@y.example"))
        assertFalse(Addresses.isValidAddrSpec("<jdoe@machine.example>"))
        assertFalse(Addresses.isValidAddrSpec("j doe@machine.example"))
    }

    @Test
    fun rfc5322AppendixA1() {
        assertEquals(listOf(Address("John Doe", "jdoe@machine.example")), parse("John Doe <jdoe@machine.example>"))
        assertEquals(
            listOf(Address("Joe Q. Public", "john.q.public@example.com")),
            parse("\"Joe Q. Public\" <john.q.public@example.com>"),
        )
        assertEquals(
            listOf(
                Address("Mary Smith", "mary@x.test"),
                Address(null, "jdoe@example.org"),
                Address("Who?", "one@y.test"),
            ),
            parse("Mary Smith <mary@x.test>, jdoe@example.org, Who? <one@y.test>"),
        )
        assertEquals(
            listOf(Address(null, "boss@nil.test"), Address("Giant; \"Big\" Box", "sysservices@example.net")),
            parse("<boss@nil.test>, \"Giant; \\\"Big\\\" Box\" <sysservices@example.net>"),
        )
    }

    @Test
    fun groups() {
        assertEquals(
            listOf(Address("Ed Jones", "c@a.test"), Address(null, "joe@where.test"), Address("John", "jdoe@one.test")),
            parse("A Group:Ed Jones <c@a.test>,joe@where.test,John <jdoe@one.test>;"),
        )
        assertEquals(emptyList(), parse("Undisclosed recipients:;"))
        assertEquals(emptyList(), parse("undisclosed-recipients:;"))
        assertEquals(
            listOf(Address(null, "a@b.example"), Address(null, "c@d.example"), Address("Eve", "eve@x.example")),
            parse("Team: a@b.example, c@d.example; Eve <eve@x.example>"),
        )
    }

    @Test
    fun rfc5322AppendixA5Comments() {
        assertEquals(
            listOf(Address("Pete", "pete@silly.test")),
            parse("Pete(A nice \\) chap) <pete(his account)@silly.test(his host)>"),
        )
        assertEquals(
            listOf(
                Address("Chris Jones", "c@public.example"),
                Address(null, "joe@example.org"),
                Address("John", "jdoe@one.test"),
            ),
            parse(
                "A Group(Some people)\r\n     :Chris Jones <c@(Chris's host.)public.example>,\r\n" +
                    "         joe@example.org,\r\n  John <jdoe@one.test> (my dear friend); (the end of the group)",
            ),
        )
    }

    @Test
    fun rfc5322AppendixA6Obsolete() {
        assertEquals(
            listOf(Address("Mary Smith", "mary@example.net"), Address(null, "jdoe@test.example")),
            parse("Mary Smith <@node.test:mary@example.net>, , jdoe@test  . example"),
        )
        assertEquals(
            listOf(Address("John Doe", "jdoe@machine.example")),
            parse("John Doe <jdoe@machine(comment).  example>"),
        )
        assertEquals(listOf(Address("Mary Smith", "mary@example.net")), parse("Mary Smith\r\n  \r\n          <mary@example.net>"))
        assertEquals(listOf(Address(null, "a@b.example")), parse("<@r1.example,@r2.example:a@b.example>"))
        assertEquals(listOf(Address(null, "john.doe@example.org")), parse("john . doe @ example . org"))
    }

    @Test
    fun legacyCommentNames() {
        assertEquals(listOf(Address("John Doe", "john@example.org")), parse("john@example.org (John Doe)"))
        assertEquals(listOf(Address("John", "john@example.org")), parse("<john@example.org> (John)"))
        assertEquals(
            listOf(Address("A", "a@x.example"), Address("B", "b@x.example")),
            parse("a@x.example (A), b@x.example (B)"),
        )
    }

    @Test
    fun gmailAndOutlookStyles() {
        assertEquals(listOf(Address("Google", "no-reply@accounts.google.com")), parse("Google <no-reply@accounts.google.com>"))
        assertEquals(listOf(Address("Doe, John", "John.Doe@outlook.com")), parse("\"Doe, John\" <John.Doe@outlook.com>"))
        assertEquals(
            listOf(Address("Jan Novák", "jan.novak@gmail.com"), Address("Petr", "petr@riseup.net")),
            parse("=?UTF-8?Q?Jan_Nov=C3=A1k?= <jan.novak@gmail.com>, Petr <petr@riseup.net>"),
        )
        assertEquals(
            listOf(Address("'Smith, Anne'", "anne.smith@example.com")),
            parse("\"'Smith, Anne'\" <anne.smith@example.com>"),
        )
    }

    @Test
    fun appleMailAndMailingLists() {
        assertEquals(
            listOf(Address("Jörg Müller", "joerg@icloud.com")),
            parse("=?utf-8?Q?J=C3=B6rg_M=C3=BCller?= <joerg@icloud.com>"),
        )
        assertEquals(
            listOf(Address("John Doe via dev-list", "dev-list@lists.example.org")),
            parse("John Doe via dev-list <dev-list@lists.example.org>"),
        )
        assertEquals(
            listOf(Address("John Doe (via Google Docs)", "comments-noreply@docs.google.com")),
            parse("\"John Doe (via Google Docs)\" <comments-noreply@docs.google.com>"),
        )
    }

    @Test
    fun encodedWordsInNames() {
        assertEquals(listOf(Address("张三", "zhang@example.cn")), parse("=?utf-8?B?5byg5LiJ?= <zhang@example.cn>"))
        assertEquals(listOf(Address("Éric", "eric@example.fr")), parse("\"=?UTF-8?B?w4lyaWM=?=\" <eric@example.fr>"))
        assertEquals(
            listOf(Address("Smith, John", "js@example.com")),
            parse("=?utf-8?q?Smith,_John?= <js@example.com>"),
        )
        assertEquals(
            listOf(Address("Jörg Müller", "j@example.de")),
            parse("=?utf-8?q?J=C3=B6rg?= =?utf-8?q?_M=C3=BCller?= <j@example.de>"),
        )
    }

    @Test
    fun unicodeAddresses() {
        assertEquals(listOf(Address("Jörg", "jörg@exämple.de")), parse("Jörg <jörg@exämple.de>"))
        assertEquals(listOf(Address(null, "用户@例子.广告")), parse("用户@例子.广告"))
    }

    @Test
    fun missingCommasAndStrayWhitespace() {
        assertEquals(
            listOf(Address("A", "a@x.example"), Address("B", "b@x.example")),
            parse("\"A\" <a@x.example> \"B\" <b@x.example>"),
        )
        assertEquals(
            listOf(Address(null, "a@x.example"), Address(null, "b@x.example")),
            parse("<a@x.example><b@x.example>"),
        )
        assertEquals(
            listOf(Address(null, "a@x.example"), Address(null, "b@x.example")),
            parse("  a@x.example   b@x.example  "),
        )
        assertEquals(listOf(Address(null, "a@x.example")), parse("\t< a@x.example >\t"))
        assertEquals(
            listOf(Address(null, "a@x.example"), Address(null, "b@x.example")),
            parse("a@x.example;b@x.example,,"),
        )
    }

    @Test
    fun missingAngleBrackets() {
        assertEquals(listOf(Address("John Doe", "john@x.example")), parse("John Doe john@x.example"))
        assertEquals(listOf(Address("John Doe", "john@x.example")), parse("\"John Doe\" john@x.example"))
        assertEquals(listOf(Address("John", "j@x.example")), parse("John <j@x.example"))
    }

    @Test
    fun quotedLocalParts() {
        assertEquals(listOf(Address(null, "\"john doe\"@example.com")), parse("\"john doe\"@example.com"))
        assertEquals(listOf(Address("J", "\"j d\"@example.com")), parse("J <\"j d\"@example.com>"))
        assertEquals(listOf(Address(null, "\"a,b\"@example.com")), parse("<\"a,b\"@example.com>"))
    }

    @Test
    fun namesAreNormalised() {
        assertEquals(listOf(Address(null, "a@x.example")), parse("\"\" <a@x.example>"))
        assertEquals(listOf(Address(null, "a@x.example")), parse("\"   \" <a@x.example>"))
        assertEquals(listOf(Address("A B", "a@x.example")), parse("\"A\tB  \" <a@x.example>"))
        assertEquals(listOf(Address("JohnDoe", "a@x.example")), parse("\"John\"Doe <a@x.example>"))
        assertEquals(listOf(Address("John Doe", "a@x.example")), parse("John (middle) Doe <a@x.example>"))
    }

    @Test
    fun entriesWithoutAddressAreSkipped() {
        assertEquals(emptyList(), parse(""))
        assertEquals(emptyList(), parse("   "))
        assertEquals(emptyList(), parse("John Doe"))
        assertEquals(emptyList(), parse("<>"))
        assertEquals(emptyList(), parse("\"Nobody\" <>"))
        assertEquals(listOf(Address("Mail Delivery System", "MAILER-DAEMON")), parse("Mail Delivery System <MAILER-DAEMON>"))
    }

    @Test
    fun injectionInParsedNames() {
        val list = parse("=?UTF-8?B?RXZpbA0KQmNjOiB2aWN0aW1AZXhhbXBsZS5jb20=?= <evil@example.com>")
        assertEquals(listOf(Address("Evil Bcc: victim@example.com", "evil@example.com")), list)
        val crlf = parse("\"Evil\r\nBcc: x@y.example\" <evil@example.com>")
        assertEquals(listOf(Address("Evil Bcc: x@y.example", "evil@example.com")), crlf)
    }

    @Test
    fun hiddenCharactersInEmailsAreShown() {
        assertEquals(listOf(Address("PayPal", "support@pay\uFFFDmoc.lap")), parse("PayPal <support@pay\u202Emoc.lap>"))
        assertEquals(listOf(Address(null, "support@pay\uFFFDpal.com")), parse("support@pay\u200Bpal.com"))
        assertEquals(listOf(Address(null, "support@pay\uFFFDpal.com")), parse("<support@pay\uFE0Fpal.com>"))
        // Only the email changes; the name is shown with its own rules.
        assertEquals(listOf(Address("A\u200BB", "\"a\uFFFDb\"@x.example")), parse("\"A\u200BB\" <\"a\u0085b\"@x.example>"))
        // A supplementary character becomes one U+FFFD.
        assertEquals(listOf(Address(null, "a\uFFFD@x.example")), parse("a\uDB40\uDC61@x.example"))
        for (email in listOf("jörg@exämple.de", "用户@例子.广告", "संपर्क@डाटामेल.भारत", "user@مثال.إختبار")) {
            assertEquals(listOf(Address(null, email)), parse(email))
        }
    }

    @Test
    fun formatSimple() {
        assertEquals("a@x.example", Address(null, "a@x.example").format())
        assertEquals("a@x.example", Address("", "a@x.example").format())
        assertEquals("a@x.example", Address("  \r\n ", "a@x.example").format())
        assertEquals("John Doe <jdoe@x.example>", Address("John Doe", "jdoe@x.example").format())
        assertEquals("a@x.example, B <b@x.example>", Addresses.format(listOf(Address(null, "a@x.example"), Address("B", "b@x.example"))))
        assertEquals("", Addresses.format(emptyList()))
    }

    @Test
    fun formatQuotesSpecials() {
        assertEquals("\"Doe, John\" <j@x.example>", Address("Doe, John", "j@x.example").format())
        assertEquals("\"Joe Q. Public\" <j@x.example>", Address("Joe Q. Public", "j@x.example").format())
        assertEquals("\"Giant; \\\"Big\\\" Box\" <s@x.example>", Address("Giant; \"Big\" Box", "s@x.example").format())
        assertEquals("\"back\\\\slash\" <s@x.example>", Address("back\\slash", "s@x.example").format())
        for (c in "()<>[]:;@\\,.\"") assertTrue(Address("a${c}b", "s@x.example").format().startsWith("\""))
    }

    @Test
    fun formatEncodesNonAscii() {
        assertEquals("=?UTF-8?B?SmFuIE5vdsOhaw==?= <jan@x.example>", Address("Jan Novák", "jan@x.example").format())
        // Non-ASCII with specials: encoded word, never inside quotes.
        val f = Address("Novák, Jan", "jan@x.example").format()
        assertFalse(f.startsWith("\""))
        assertEquals(listOf(Address("Novák, Jan", "jan@x.example")), parse(f))
    }

    @Test
    fun formatWithoutEncodingKeepsNonAscii() {
        val list = listOf(Address("Novák, Jan", "jan@x.example"), Address("Zoë", "z@x.example"), Address(null, "a@x.example"))
        val f = Addresses.format(list, encode = false)
        assertEquals("\"Novák, Jan\" <jan@x.example>, Zoë <z@x.example>, a@x.example", f)
        assertEquals(list, parse(f))
        assertEquals("x y <a@x.example>", Addresses.format(listOf(Address("x\r\ny", "a@x.example")), encode = false))
    }

    @Test
    fun formatNeutralisesInjection() {
        val f = Addresses.format(
            listOf(
                Address("Evil\r\nBcc: victim@x.example", "a@x.example"),
                Address("Name", "b@x.example\r\nBcc: victim@x.example"),
                Address("Tab\tand\u0000nul\u007F", "c@x.example"),
            ),
        )
        assertFalse(f.any { it == '\r' || it == '\n' || it < ' ' || it == '\u007F' })
        assertEquals(
            "\"Evil Bcc: victim@x.example\" <a@x.example>, Name <b@x.example Bcc: victim@x.example>, " +
                "Tab and nul <c@x.example>",
            f,
        )
    }

    @Test
    fun formatThenParseRoundTrip() {
        val list = listOf(
            Address("Doe, John", "jdoe@x.example"),
            Address("Jörg \"JM\" Müller", "joerg@x.example"),
            Address(null, "plain@x.example"),
            Address("日本語の名前がとても長い場合でもちゃんと分割されるべきです", "jp@x.example"),
            Address("a\\b \"c\"", "q@x.example"),
        )
        assertEquals(list, parse(Addresses.format(list)))
    }

    @Test
    fun randomInputNeverThrowsAndRoundTripsSafely() {
        val rnd = Random(1)
        val alphabet = "\"a@b.c<>(),;:\\ \t\r\n=?_qQbB%*'é日\u0000"
        repeat(3000) {
            val s = String(CharArray(rnd.nextInt(0, 60)) { alphabet[rnd.nextInt(alphabet.length)] })
            for (a in parse(s)) {
                assertTrue(a.email.isNotEmpty())
                assertFalse(Addresses.format(listOf(a)).any { it < ' ' || it == '\u007F' })
            }
        }
    }

    @Test(timeout = 5_000)
    fun hostileInputStaysFast() {
        assertEquals(emptyList(), parse("\"\"".repeat(200_000)))
        assertEquals(1, parse("a@b" + " .".repeat(100_000)).size)
        assertEquals(emptyList(), parse("(".repeat(200_000)))
        assertEquals(emptyList(), parse("=?a?q?" + "x,".repeat(200_000)))
        assertEquals(emptyList(), parse("=?".repeat(200_000)))
        assertEquals(100_000, parse("a@b.example,".repeat(100_000)).size)
        assertEquals(1, parse("<" + "\"".repeat(200_000)).size)
    }
}
