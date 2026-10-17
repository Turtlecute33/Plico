package app.plico.data

import java.io.File
import java.nio.file.Files as NioFiles
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesTest {
    private val base: File = NioFiles.createTempDirectory("plico-files").toFile()

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun keepsOrdinaryNames() {
        assertEquals("Report 2026 (final).pdf", Files.safeName("Report 2026 (final).pdf"))
        assertEquals("Übersicht — 日本語.txt", Files.safeName("Übersicht — 日本語.txt"))
        assertEquals("a.b.c", Files.safeName("a.b.c"))
    }

    @Test
    fun replacesSeparatorsAndColon() {
        assertEquals("_.._.._shared_prefs_plico.xml", Files.safeName("/../../shared_prefs/plico.xml"))
        assertEquals("_.._.._x", Files.safeName("\\..\\..\\x"))
        assertEquals("C__evil", Files.safeName("C:/evil"))
        assertEquals("a_b", Files.safeName("a:b"))
    }

    @Test
    fun stripsLeadingDots() {
        assertEquals("_.._shared_prefs_x.xml", Files.safeName("../../shared_prefs/x.xml"))
        assertEquals("bashrc", Files.safeName(".bashrc"))
        assertEquals("hidden.txt", Files.safeName("...hidden.txt"))
    }

    @Test
    fun percentEncodingStaysLiteral() {
        // Names are never URL-decoded, so %2F cannot become a separator.
        val name = Files.safeName("..%2F..%2Fshared_prefs%2Fplico.xml")
        assertEquals("%2F..%2Fshared_prefs%2Fplico.xml", name)
        assertFalse('/' in name)
    }

    @Test
    fun replacesNulAndControls() {
        assertEquals("evil.pdf_.exe", Files.safeName("evil.pdf\u0000.exe"))
        assertEquals("a__b_c", Files.safeName("a\r\nb\u0007c"))
        assertEquals("tab_cr_lf_del_c1_", Files.safeName("tab\tcr\rlf\ndel\u007fc1\u0085"))
    }

    @Test
    fun replacesBidiControls() {
        assertEquals("invoice_fdp.exe", Files.safeName("invoice\u202Efdp.exe"))
        for (c in listOf('\u202A', '\u202B', '\u202C', '\u202D', '\u202E', '\u2066', '\u2067', '\u2068', '\u2069')) {
            assertEquals("a_b", Files.safeName("a${c}b"), "U+%04X".format(c.code))
        }
        // Neighbours of the ranges are ordinary characters.
        assertEquals("a\u2029b", Files.safeName("a\u2029b"))
        assertEquals("a\u206Ab", Files.safeName("a\u206Ab"))
    }

    @Test
    fun fallsBackWhenNothingIsLeft() {
        assertEquals("attachment", Files.safeName(""))
        assertEquals("attachment", Files.safeName("."))
        assertEquals("attachment", Files.safeName(".."))
        assertEquals("attachment", Files.safeName("...."))
    }

    @Test
    fun limitsLength() {
        assertEquals(100, Files.safeName("a".repeat(500) + ".pdf").length)
        // 100 CJK characters are 300 UTF-8 bytes; the name is cut to fit 255 bytes.
        val cjk = Files.safeName("日".repeat(150))
        assertEquals(85, cjk.length)
        assertTrue(cjk.toByteArray().size <= 255)
    }

    @Test
    fun neverSplitsSurrogatePairs() {
        val emoji = "\uD83D\uDE00" // one code point, two chars
        val name = Files.safeName("a" + emoji.repeat(60))
        assertFalse(Character.isHighSurrogate(name.last()))
        assertTrue(name.toByteArray().size <= 255)
        assertEquals(name, String(name.toByteArray()))
    }

    @Test
    fun insideResolvesChildren() {
        val file = Files.inside(base, "12", "34")
        assertEquals(File(base.canonicalFile, "12/34"), file)
        assertEquals(File(base.canonicalFile, "a"), Files.inside(base, "x", "..", "a"))
    }

    @Test
    fun insideRejectsTraversal() {
        assertFailsWith<SecurityException> { Files.inside(base, "..", "shared_prefs", "plico.xml") }
        assertFailsWith<SecurityException> { Files.inside(base, "12", "..", "..", "x") }
        assertFailsWith<SecurityException> { Files.inside(base, "/etc/passwd/..", "..", "..") }
        assertFailsWith<SecurityException> { Files.inside(base, ".") }
        assertFailsWith<SecurityException> { Files.inside(base) }
    }

    @Test
    fun insideRejectsSiblingWithSamePrefix() {
        // base + "-other" starts with the base path string but is outside it.
        assertFailsWith<SecurityException> { Files.inside(base, "..", base.name + "-other", "x") }
    }

    @Test
    fun insideRejectsSymlinkOut() {
        val outside = NioFiles.createTempDirectory("plico-outside").toFile()
        try {
            NioFiles.createSymbolicLink(File(base, "link").toPath(), outside.toPath())
            assertFailsWith<SecurityException> { Files.inside(base, "link", "x") }
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun logoPathsStayInTheLogoDir() {
        val files = Files(cacheDir = base, filesDir = base)
        val dir = File(base, "logos")
        assertEquals(dir, files.logoDir())
        val hash = "0123456789abcdef".repeat(4)
        val png = files.logo(hash, "png")
        assertEquals(File(dir.canonicalFile, "$hash.png"), png)
        assertTrue(dir.isDirectory)
        assertEquals(File(dir.canonicalFile, "$hash.none"), files.logo(hash, "none"))
    }

    @Test
    fun logoPathsRefuseOtherNames() {
        val files = Files(cacheDir = base, filesDir = base)
        val hash = "a".repeat(64)
        for (bad in listOf("../" + "a".repeat(61), "A".repeat(64), "a".repeat(63), "", "example.com", "a".repeat(65))) {
            assertFailsWith<IllegalArgumentException>(bad) { files.logo(bad, "png") }
        }
        for (bad in listOf("", "PNG", "png/../../x", ".png", "png.tmp", "p g")) {
            assertFailsWith<IllegalArgumentException>(bad) { files.logo(hash, bad) }
        }
    }

    @Test
    fun safeNamesStayInside() {
        val hostile = listOf(
            "../../shared_prefs/plico.xml", "..\\..\\databases\\plico.db", "..%2F..%2Fx", "\u0000",
            "a/../../b", "\u202Egnp.exe", ".", "..", "/", "x".repeat(300),
        )
        for (name in hostile) {
            val file = Files.inside(base, "7", Files.safeName(name))
            assertEquals(File(base.canonicalFile, "7"), file.parentFile, name)
        }
    }
}
