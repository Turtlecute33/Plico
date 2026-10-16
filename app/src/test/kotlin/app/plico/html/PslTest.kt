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
        assertEquals("com", psl.publicSuffix("www.example.com"))
        assertEquals("example.co.uk", psl.registrableDomain("www.example.co.uk"))
        assertEquals("co.uk", psl.publicSuffix("a.b.example.co.uk"))
        assertEquals("bbc.co.uk", psl.registrableDomain("news.bbc.co.uk"))
        assertNull(psl.registrableDomain("co.uk"))
        assertNull(psl.registrableDomain("com"))
        assertEquals("co.uk", psl.publicSuffix("co.uk"))
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
        assertEquals("foo.ck", psl.publicSuffix("foo.ck"))
        assertNull(psl.registrableDomain("foo.ck"))
        assertEquals("a.foo.ck", psl.registrableDomain("www.a.foo.ck"))
        // *.kawasaki.jp
        assertNull(psl.registrableDomain("foo.kawasaki.jp"))
        assertEquals("x.foo.kawasaki.jp", psl.registrableDomain("x.foo.kawasaki.jp"))
    }

    @Test
    fun exceptionRules() {
        // !www.ck with *.ck
        assertEquals("ck", psl.publicSuffix("www.ck"))
        assertEquals("www.ck", psl.registrableDomain("www.ck"))
        assertEquals("www.ck", psl.registrableDomain("a.www.ck"))
        // !city.kawasaki.jp with *.kawasaki.jp
        assertEquals("kawasaki.jp", psl.publicSuffix("city.kawasaki.jp"))
        assertEquals("city.kawasaki.jp", psl.registrableDomain("www.city.kawasaki.jp"))
    }

    @Test
    fun unlistedTldUsesTheDefaultRule() {
        assertEquals("notatld", psl.publicSuffix("www.example.notatld"))
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
        assertNull(psl.publicSuffix("bad host.com"))
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
        assertEquals("tokyo.jp", small.publicSuffix("a.tokyo.jp"))
        assertEquals("a.tokyo.jp", small.registrableDomain("b.a.tokyo.jp"))
        assertEquals("pref.jp", small.registrableDomain("x.pref.jp"))
        assertEquals("a.co.uk", small.registrableDomain("x.a.co.uk"))
        assertEquals("org", small.publicSuffix("a.org"))
    }

    @Test
    fun domainsInText() {
        assertEquals(listOf("paypal.com"), psl.domainsIn("Sign in at https://www.PayPal.com/signin now"))
        assertEquals(listOf("example.co.uk", "x.github.io"), psl.domainsIn("example.co.uk and x.github.io"))
        assertEquals(emptyList(), psl.domainsIn("Version 2.0, e.g. file.notatld or 10.0.0.1"))
        assertEquals(emptyList(), psl.domainsIn("Click here"))
    }

    @Test
    fun bundledListLoads() {
        val text = File("src/main/assets/public_suffix_list.dat").readText()
        assertTrue(text.startsWith("// This Source Code Form is subject to the terms of the Mozilla Public"))
        assertTrue("===BEGIN PRIVATE DOMAINS===" in text)
    }
}
