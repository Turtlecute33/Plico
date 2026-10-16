package app.plico.mail.mime

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncodedWordsTest {
    @Test
    fun plainTextIsUnchanged() {
        assertEquals("Hello world", EncodedWords.decode("Hello world"))
        assertEquals("", EncodedWords.decode(""))
        assertEquals("a =? b ?= c", EncodedWords.decode("a =? b ?= c"))
    }

    @Test
    fun rfc2047Section8Examples() {
        assertEquals("Keith Moore", EncodedWords.decode("=?US-ASCII?Q?Keith_Moore?="))
        assertEquals("Keld Jørn Simonsen", EncodedWords.decode("=?ISO-8859-1?Q?Keld_J=F8rn_Simonsen?="))
        assertEquals("André Pirard", EncodedWords.decode("=?ISO-8859-1?Q?Andr=E9?= Pirard"))
        assertEquals(
            "If you can read this you understand the example.",
            EncodedWords.decode(
                "=?ISO-8859-1?B?SWYgeW91IGNhbiByZWFkIHRoaXMgeW8=?= =?ISO-8859-2?B?dSB1bmRlcnN0YW5kIHRoZSBleGFtcGxlLg==?=",
            ),
        )
    }

    @Test
    fun rfc2047WhitespaceTable() {
        assertEquals("(a)", EncodedWords.decode("(=?ISO-8859-1?Q?a?=)"))
        assertEquals("(a b)", EncodedWords.decode("(=?ISO-8859-1?Q?a?= b)"))
        assertEquals("(ab)", EncodedWords.decode("(=?ISO-8859-1?Q?a?= =?ISO-8859-1?Q?b?=)"))
        assertEquals("(ab)", EncodedWords.decode("(=?ISO-8859-1?Q?a?=  =?ISO-8859-1?Q?b?=)"))
        assertEquals("(ab)", EncodedWords.decode("(=?ISO-8859-1?Q?a?=\r\n    =?ISO-8859-1?Q?b?=)"))
        assertEquals("(ab)", EncodedWords.decode("(=?ISO-8859-1?Q?a?=\t=?ISO-8859-1?Q?b?=)"))
        assertEquals("(a b)", EncodedWords.decode("(=?ISO-8859-1?Q?a_b?=)"))
        assertEquals("(a b)", EncodedWords.decode("(=?ISO-8859-1?Q?a?= =?ISO-8859-2?Q?_b?=)"))
    }

    @Test
    fun rfc2231LanguageSuffix() {
        assertEquals("Keith Moore", EncodedWords.decode("=?US-ASCII*EN?Q?Keith_Moore?="))
    }

    @Test
    fun caseInsensitiveEncodingAndCharset() {
        assertEquals("Café", EncodedWords.decode("=?utf-8?q?Caf=C3=A9?="))
        assertEquals("Café", EncodedWords.decode("=?UTF-8?Q?Caf=c3=a9?="))
        assertEquals("Éric", EncodedWords.decode("=?Utf-8?b?w4lyaWM=?="))
    }

    @Test
    fun gmailSubjects() {
        assertEquals("Re: Čau, jak se máš?", EncodedWords.decode("=?UTF-8?B?UmU6IMSMYXUsIGphayBzZSBtw6HFoT8=?="))
        assertEquals(
            "Security alert for your linked Google Account",
            EncodedWords.decode("=?UTF-8?Q?Security_alert_for_your_linked_Google_Account?="),
        )
        assertEquals("Party 🎉 time", EncodedWords.decode("=?UTF-8?B?UGFydHkg8J+OiSB0aW1l?="))
    }

    @Test
    fun mixedPlainAndEncodedText() {
        assertEquals("[dev] Café meeting", EncodedWords.decode("[dev] =?utf-8?q?Caf=C3=A9?= meeting"))
        assertEquals("Fwd: 日本語のテスト!", EncodedWords.decode("Fwd: =?UTF-8?B?5pel5pys6Kqe44Gu44OG44K544OI?=!"))
    }

    @Test
    fun outlookAndAsianCharsets() {
        assertEquals("안녕하세요", EncodedWords.decode("=?ks_c_5601-1987?B?vsiz58fPvLy/5A==?="))
        assertEquals("שלום", EncodedWords.decode("=?iso-8859-8-i?B?+ezl7Q==?="))
        assertEquals("会議の件", EncodedWords.decode("=?ISO-2022-JP?B?GyRCMnE1RCRON28bKEI=?="))
        assertEquals("测试邮件", EncodedWords.decode("=?gb2312?B?suLK1NPKvP4=?="))
        assertEquals("Привет, мир", EncodedWords.decode("=?utf-8?B?0J/RgNC40LLQtdGCLCDQvNC40YA=?="))
    }

    @Test
    fun characterSplitAcrossAdjacentWords() {
        assertEquals("éric", EncodedWords.decode("=?UTF-8?B?ww==?= =?UTF-8?B?qXJpYw==?="))
        assertEquals("é", EncodedWords.decode("=?utf-8?q?=C3?= =?utf-8?q?=A9?="))
    }

    @Test
    fun adjacentWordsWithoutWhitespace() {
        assertEquals("ab", EncodedWords.decode("=?utf-8?q?a?==?utf-8?q?b?="))
        assertEquals("xay", EncodedWords.decode("x=?utf-8?q?a?=y"))
    }

    @Test
    fun brokenMailerSpacesInsideQText() {
        assertEquals("this is some text", EncodedWords.decode("=?iso-8859-1?q?this is some text?="))
    }

    @Test
    fun encodedWordsInsideQuotes() {
        assertEquals("\"Éric\" <eric@example.fr>", EncodedWords.decode("\"=?UTF-8?B?w4lyaWM=?=\" <eric@example.fr>"))
    }

    @Test
    fun malformedWordsStayAsIs() {
        assertEquals("=?utf-8?x?abc?=", EncodedWords.decode("=?utf-8?x?abc?="))
        assertEquals("=?utf-8?q?abc", EncodedWords.decode("=?utf-8?q?abc"))
        assertEquals("=??q?abc?=", EncodedWords.decode("=??q?abc?="))
        assertEquals("", EncodedWords.decode("=?utf-8?b??="))
    }

    @Test
    fun unknownCharsetFallsBackToUtf8ThenWindows1252() {
        assertEquals("Café", EncodedWords.decode("=?x-unknown?q?Caf=C3=A9?="))
        assertEquals("Café", EncodedWords.decode("=?unknown-8bit?q?Caf=E9?="))
        assertEquals("Café", EncodedWords.decode("=?utf-8?q?Caf=E9?="))
    }

    @Test
    fun brokenBase64IsTolerated() {
        assertEquals("Éric", EncodedWords.decode("=?UTF-8?B?w4lyaWM?="))
        assertEquals("Éric", EncodedWords.decode("=?UTF-8?B?w4ly aWM=?="))
    }

    @Test
    fun injectionDecodesButCleanNeutralises() {
        val q = EncodedWords.decode("=?UTF-8?Q?Hello=0D=0ABcc:_victim@example.com?=")
        assertEquals("Hello\r\nBcc: victim@example.com", q)
        assertEquals("Hello Bcc: victim@example.com", HeaderSafety.clean(q))
        val b = EncodedWords.decode("=?UTF-8?B?RXZpbA0KQmNjOiB2aWN0aW1AZXhhbXBsZS5jb20=?=")
        assertEquals("Evil\r\nBcc: victim@example.com", b)
        assertEquals("Evil Bcc: victim@example.com", HeaderSafety.clean(b))
    }

    @Test
    fun encodeKeepsPrintableAscii() {
        assertEquals("Hello, world! ~", EncodedWords.encode("Hello, world! ~"))
        assertEquals("", EncodedWords.encode(""))
    }

    @Test
    fun encodeNonAscii() {
        assertEquals("=?UTF-8?B?Q2Fmw6k=?=", EncodedWords.encode("Café"))
        assertEquals("Café", EncodedWords.decode(EncodedWords.encode("Café")))
    }

    @Test
    fun encodeControlCharsIsNotAscii() {
        val encoded = EncodedWords.encode("a\r\nb")
        assertTrue(encoded.startsWith("=?UTF-8?B?"))
        assertTrue(encoded.none { it == '\r' || it == '\n' })
        assertEquals("a\r\nb", EncodedWords.decode(encoded))
    }

    @Test
    fun encodeSplitsLongValuesIntoShortWords() {
        val samples = listOf(
            "Žluťoučký kůň úpěl ďábelské ódy ".repeat(8),
            "日本語のテスト".repeat(20),
            "🎉😀👍".repeat(30),
            "a" + "é".repeat(100),
        )
        for (s in samples) {
            val encoded = EncodedWords.encode(s)
            val words = encoded.split(' ')
            assertTrue(words.size > 1)
            for (w in words) {
                assertTrue(w.length <= 75, "word too long: ${w.length}")
                assertTrue(w.startsWith("=?UTF-8?B?") && w.endsWith("?="))
                // Each word on its own must be valid UTF-8: no split sequences or surrogate pairs.
                val payload = w.removePrefix("=?UTF-8?B?").removeSuffix("?=")
                val bytes = java.util.Base64.getDecoder().decode(payload)
                assertEquals(String(bytes, Charsets.UTF_8), TransferDecoding.utf8OrNull(bytes))
            }
            assertEquals(s, EncodedWords.decode(encoded))
        }
    }

    @Test
    fun randomInputNeverThrows() {
        val rnd = Random(4)
        val alphabet = "\"a@b.c<>(),;:\\ \t\r\n=?_qQbB%*'é日\u0000=?utf-8?B?Q?ks_c_5601-1987?"
        repeat(3000) {
            val s = String(CharArray(rnd.nextInt(0, 60)) { alphabet[rnd.nextInt(alphabet.length)] })
            EncodedWords.decode(s)
            if (s.any { it !in ' '..'~' }) assertEquals(s, EncodedWords.decode(EncodedWords.encode(s)))
        }
    }

    @Test(timeout = 5_000)
    fun hostileInputStaysFast() {
        val long = "=?a?q?" + "x".repeat(500_000)
        assertEquals(long, EncodedWords.decode(long))
        assertEquals("=?".repeat(200_000), EncodedWords.decode("=?".repeat(200_000)))
        assertEquals("a".repeat(100_000), EncodedWords.decode("=?utf-8?q?a?= ".repeat(100_000).trim()))
        assertTrue(EncodedWords.encode("é".repeat(200_000)).length > 400_000)
    }
}
