package app.plico.html

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PslTest {
    private val psl = testPsl

    @Test
    fun icannSuffixes() {
        assertEquals("example.com", psl.registrableDomain("www.example.com"))
        assertEquals("example.co.uk", psl.registrableDomain("www.example.co.uk"))
        assertEquals("example.co.uk", psl.registrableDomain("a.b.example.co.uk"))
        assertEquals("bbc.co.uk", psl.registrableDomain("news.bbc.co.uk"))
        assertNull(psl.registrableDomain("co.uk"))
        assertNull(psl.registrableDomain("com"))
    }

    @Test
    fun privateSuffixes() {
        assertEquals("evil.github.io", psl.registrableDomain("evil.github.io"))
        assertEquals("b.github.io", psl.registrableDomain("a.b.github.io"))
        assertNull(psl.registrableDomain("github.io"))
        assertEquals("phish.web.app", psl.registrableDomain("phish.web.app"))
        assertEquals("x.pages.dev", psl.registrableDomain("www.x.pages.dev"))
        assertEquals("blog.blogspot.com", psl.registrableDomain("blog.blogspot.com"))
    }

    @Test
    fun wildcardRules() {
        // *.ck
        assertNull(psl.registrableDomain("foo.ck"))
        assertEquals("a.foo.ck", psl.registrableDomain("www.a.foo.ck"))
        // *.kawasaki.jp
        assertNull(psl.registrableDomain("foo.kawasaki.jp"))
        assertEquals("x.foo.kawasaki.jp", psl.registrableDomain("x.foo.kawasaki.jp"))
    }

    @Test
    fun exceptionRules() {
        // !www.ck with *.ck
        assertEquals("www.ck", psl.registrableDomain("www.ck"))
        assertEquals("www.ck", psl.registrableDomain("a.www.ck"))
        // !city.kawasaki.jp with *.kawasaki.jp
        assertEquals("city.kawasaki.jp", psl.registrableDomain("city.kawasaki.jp"))
        assertEquals("city.kawasaki.jp", psl.registrableDomain("www.city.kawasaki.jp"))
    }

    @Test
    fun unlistedTldUsesTheDefaultRule() {
        assertEquals("example.notatld", psl.registrableDomain("www.example.notatld"))
        assertFalse(psl.hasListedSuffix("example.notatld"))
        assertTrue(psl.hasListedSuffix("example.com"))
        assertTrue(psl.hasListedSuffix("x.github.io"))
    }

    @Test
    fun unicodeRulesAndHosts() {
        // 公司.cn is a rule; Unicode and punycode hosts give the same ASCII result.
        assertEquals("example.xn--55qx5d.cn", psl.registrableDomain("www.example.公司.cn"))
        assertEquals("example.xn--55qx5d.cn", psl.registrableDomain("www.example.xn--55qx5d.cn"))
        assertEquals("xn--e1afmkfd.xn--p1ai", psl.registrableDomain("пример.рф"))
    }

    @Test
    fun normalization() {
        assertEquals("example.com", psl.registrableDomain("WWW.Example.COM."))
        assertNull(psl.registrableDomain("192.168.1.1"))
        assertNull(psl.registrableDomain("[::1]"))
        assertNull(psl.registrableDomain(""))
        assertNull(psl.registrableDomain("a..b.com"))
        assertNull(psl.registrableDomain("bad host.com"))
    }

    @Test
    fun smallListAlgorithm() {
        val small = Psl(
            """
            // comment
            com
            *.jp
            !pref.jp
            uk
            co.uk   trailing text is ignored
            """.trimIndent(),
        )
        assertEquals("a.com", small.registrableDomain("x.a.com"))
        assertEquals("a.tokyo.jp", small.registrableDomain("a.tokyo.jp"))
        assertEquals("a.tokyo.jp", small.registrableDomain("b.a.tokyo.jp"))
        assertEquals("pref.jp", small.registrableDomain("x.pref.jp"))
        assertEquals("a.co.uk", small.registrableDomain("x.a.co.uk"))
        assertEquals("a.org", small.registrableDomain("a.org"))
    }

    @Test
    fun domainsInText() {
        assertEquals(listOf("paypal.com"), psl.domainsIn("Sign in at https://www.PayPal.com/signin now"))
        assertEquals(listOf("example.co.uk", "x.github.io"), psl.domainsIn("example.co.uk and x.github.io"))
        assertEquals(emptyList(), psl.domainsIn("Version 2.0, e.g. file.notatld or 10.0.0.1"))
        assertEquals(emptyList(), psl.domainsIn("Click here"))
    }

    @Test
    fun matchedRuleRecordsTheSection() {
        val com = psl.matchedRule("www.example.com")
        assertEquals("com", com?.suffix)
        assertEquals(false, com?.private)
        assertEquals(false, psl.matchedRule("news.bbc.co.uk")?.private)
        assertEquals("co.uk", psl.matchedRule("news.bbc.co.uk")?.suffix)

        for ((host, suffix) in listOf(
            "x.github.io" to "github.io",
            "www.x.pages.dev" to "pages.dev",
            "blog.blogspot.com" to "blogspot.com",
            "a.netlify.app" to "netlify.app",
            "a.b.workers.dev" to "workers.dev",
        )) {
            val rule = psl.matchedRule(host)
            assertEquals(suffix, rule?.suffix, host)
            assertEquals(true, rule?.private, host)
        }
    }

    @Test
    fun matchedRuleOfWildcardsAndExceptions() {
        // *.ck: the wildcard gives the suffix foo.ck; !www.ck gives ck.
        assertEquals("foo.ck", psl.matchedRule("a.foo.ck")?.suffix)
        assertEquals("ck", psl.matchedRule("a.www.ck")?.suffix)
        assertEquals(false, psl.matchedRule("a.www.ck")?.private)
    }

    @Test
    fun matchedRuleIsNullForUnlistedSuffixesAndBadHosts() {
        assertNull(psl.matchedRule("example.notatld"))
        assertNull(psl.matchedRule("foo.unknowntld"))
        assertNull(psl.matchedRule("192.168.1.1"))
        assertNull(psl.matchedRule(""))
    }

    @Test
    fun sectionsOfASmallList() {
        val small = Psl(
            """
            // ===BEGIN ICANN DOMAINS===
            com
            *.jp
            // ===END ICANN DOMAINS===
            // ===BEGIN PRIVATE DOMAINS===
            // Some company
            pages.example.com
            *.cloud.example.com
            !www.cloud.example.com
            // ===END PRIVATE DOMAINS===
            org
            """.trimIndent(),
        )
        assertEquals(false, small.matchedRule("a.com")?.private)
        assertEquals(false, small.matchedRule("a.tokyo.jp")?.private)
        assertEquals(true, small.matchedRule("x.pages.example.com")?.private)
        assertEquals("x.cloud.example.com", small.matchedRule("a.x.cloud.example.com")?.suffix)
        assertEquals(true, small.matchedRule("a.x.cloud.example.com")?.private)
        assertEquals(true, small.matchedRule("a.www.cloud.example.com")?.private)
        // After the END marker the section is ICANN again.
        assertEquals(false, small.matchedRule("a.org")?.private)
        // The results that existed before stay the same.
        assertEquals("x.pages.example.com", small.registrableDomain("y.x.pages.example.com"))
        assertTrue(small.hasListedSuffix("a.org"))
        assertFalse(small.hasListedSuffix("a.net"))
    }

    @Test
    fun bundledListLoads() {
        val text = File("src/main/assets/public_suffix_list.dat").readText()
        assertTrue(text.startsWith("// This Source Code Form is subject to the terms of the Mozilla Public"))
        assertTrue("===BEGIN PRIVATE DOMAINS===" in text)
    }
}
