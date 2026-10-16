package app.plico.html

import app.plico.html.AttachmentWarning.DANGEROUS_EXTENSION
import app.plico.html.AttachmentWarning.DANGEROUS_TYPE
import app.plico.html.AttachmentWarning.DOUBLE_EXTENSION
import app.plico.html.AttachmentWarning.HIDDEN_CHARACTERS
import app.plico.html.AttachmentWarning.TYPE_MISMATCH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttachmentSafetyTest {
    private val pdf = bytes("%PDF-1.7\n%âãÏÓ\n1 0 obj")
    private val png = bytes("\u0089PNG\r\n\u001A\n\u0000\u0000\u0000\rIHDR")
    private val jpeg = bytes("ÿØÿà\u0000\u0010JFIF")
    private val zip = bytes("PK\u0003\u0004\u0014\u0000\u0000\u0000word/document.xml")
    private val apk = bytes("PK\u0003\u0004\u0014\u0000\u0000\u0000AndroidManifest.xml")
    private val html = bytes("<!DOCTYPE html><html><body><form action=https://evil.example>")

    /** A minimal PE file head: "MZ", the PE offset at 0x3C, "PE\0\0" there. */
    private val exe = ByteArray(0x90).apply {
        this[0] = 'M'.code.toByte()
        this[1] = 'Z'.code.toByte()
        this[0x3C] = 0x80.toByte()
        "PE\u0000\u0000".forEachIndexed { i, c -> this[0x80 + i] = c.code.toByte() }
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun sniffBinaryTypes() {
        assertEquals("application/pdf", AttachmentSafety.sniff(pdf))
        assertEquals("image/png", AttachmentSafety.sniff(png))
        assertEquals("image/jpeg", AttachmentSafety.sniff(jpeg))
        assertEquals("image/gif", AttachmentSafety.sniff(bytes("GIF89a\u0001\u0000")))
        assertEquals("image/gif", AttachmentSafety.sniff(bytes("GIF87a")))
        assertEquals("image/webp", AttachmentSafety.sniff(bytes("RIFF\u0010\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals("application/zip", AttachmentSafety.sniff(zip))
        assertEquals("application/zip", AttachmentSafety.sniff(bytes("PK\u0005\u0006")))
        assertEquals("application/vnd.android.package-archive", AttachmentSafety.sniff(apk))
        assertEquals("application/x-msdownload", AttachmentSafety.sniff(exe))
    }

    @Test
    fun sniffMarkup() {
        val cases = mapOf(
            "<!DOCTYPE html><p>x" to "text/html",
            "  \r\n<HTML><head>" to "text/html",
            "<!-- saved --><script>alert(1)</script>" to "text/html",
            "<iframe src=x>" to "text/html",
            "<a href=x>Sign in</a>" to "text/html",
            "<p>Hello" to "text/html",
            "ï»¿<html>" to "text/html",
            "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\">" to "application/xhtml+xml",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\">" to "image/svg+xml",
            "<?xml version=\"1.0\"?>\n<!-- x -->\n<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"x\"><svg>" to "image/svg+xml",
            "<?xml version=\"1.0\"?><svg/>" to "image/svg+xml",
            "From: <Saved by Blink>\r\nSubject: x\r\nMIME-Version: 1.0\r\nContent-Type: multipart/related;\r\n" to "multipart/related",
        )
        for ((text, type) in cases) assertEquals(type, AttachmentSafety.sniff(bytes(text)), text)
        // UTF-16 with a byte order mark.
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<html>".toByteArray(Charsets.UTF_16LE)
        assertEquals("text/html", AttachmentSafety.sniff(utf16))
    }

    @Test
    fun sniffUnknown() {
        for (text in listOf("", "Hello world", "<pre-tag", "<?xml version=\"1.0\"?><note/>", "MZ is a company", "a<html>")) {
            assertNull(AttachmentSafety.sniff(bytes(text)), text)
        }
        // "MZ" without the PE signature at the offset in the header.
        assertNull(AttachmentSafety.sniff(bytes("MZ" + "x".repeat(200))))
        assertNull(AttachmentSafety.sniff(exe.copyOf().also { it[0x3C] = 0xFF.toByte(); it[0x3F] = 0xFF.toByte() }))
    }

    @Test
    fun declaredTypeThatAgrees() {
        assertEquals(AttachmentCheck("application/pdf", emptySet()), AttachmentSafety.check("Invoice.pdf", "application/pdf", pdf))
        assertEquals(AttachmentCheck("image/jpeg", emptySet()), AttachmentSafety.check("a.jpg", "IMAGE/JPG; name=a.jpg", jpeg))
        assertEquals(
            AttachmentCheck("application/vnd.openxmlformats-officedocument.wordprocessingml.document", emptySet()),
            AttachmentSafety.check("a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip),
        )
        assertEquals(
            AttachmentCheck("text/plain", emptySet()),
            AttachmentSafety.check("notes.txt", "text/plain; charset=utf-8", bytes("Hi")),
        )
    }

    @Test
    fun mismatchUsesTheSniffedType() {
        // A web page that claims to be a PDF opens as a web page, with warnings.
        assertEquals(
            AttachmentCheck("text/html", setOf(TYPE_MISMATCH, DANGEROUS_TYPE)),
            AttachmentSafety.check("invoice.pdf", "application/pdf", html),
        )
        assertEquals(AttachmentCheck("image/png", setOf(TYPE_MISMATCH)), AttachmentSafety.check("a.jpg", "image/jpeg", png))
        assertEquals(
            AttachmentCheck("application/vnd.android.package-archive", setOf(TYPE_MISMATCH, DANGEROUS_TYPE)),
            AttachmentSafety.check("photos.zip", "application/zip", apk),
        )
        assertEquals(
            AttachmentCheck("image/svg+xml", setOf(TYPE_MISMATCH, DANGEROUS_TYPE)),
            AttachmentSafety.check("chart.png", "image/png", bytes("<svg onload=alert(1)>")),
        )
    }

    @Test
    fun genericTypeUsesTheNameThenTheContent() {
        // The name claims PDF, the content is HTML.
        assertEquals(
            AttachmentCheck("text/html", setOf(TYPE_MISMATCH, DANGEROUS_TYPE)),
            AttachmentSafety.check("invoice.pdf", "application/octet-stream", html),
        )
        assertEquals(
            AttachmentCheck("application/vnd.openxmlformats-officedocument.wordprocessingml.document", emptySet()),
            AttachmentSafety.check("report.docx", "application/octet-stream", zip),
        )
        assertEquals(AttachmentCheck("image/png", emptySet()), AttachmentSafety.check("scan", "application/octet-stream", png))
        assertEquals(AttachmentCheck("application/pdf", emptySet()), AttachmentSafety.check("doc.pdf", "", bytes("not sniffable")))
        assertEquals(
            AttachmentCheck("application/octet-stream", emptySet()),
            AttachmentSafety.check("data.bin", "application/octet-stream", bytes("x")),
        )
        assertEquals(AttachmentCheck("application/pdf", emptySet()), AttachmentSafety.check("x", "binary/octet-stream", pdf))
    }

    @Test
    fun dangerousTypes() {
        assertEquals(setOf(DANGEROUS_TYPE), AttachmentSafety.check("page", "text/html", bytes("plain words")).warnings)
        assertEquals(setOf(DANGEROUS_TYPE), AttachmentSafety.check("x", "application/xhtml+xml", bytes("")).warnings)
        assertEquals(setOf(DANGEROUS_TYPE), AttachmentSafety.check("x", "multipart/related", bytes("")).warnings)
        assertEquals(setOf(DANGEROUS_TYPE), AttachmentSafety.check("prog", "application/octet-stream", exe).warnings)
        // An HTML file declared as text/plain is shown as what it is.
        assertEquals(setOf(TYPE_MISMATCH, DANGEROUS_TYPE), AttachmentSafety.check("readme", "text/plain", html).warnings)
    }

    @Test
    fun dangerousExtensions() {
        val extensions = listOf(
            "apk", "apks", "apkm", "xapk", "exe", "msi", "bat", "cmd", "js", "jar", "scr", "vbs", "ps1", "lnk", "iso", "img", "html",
            "htm", "xhtml", "xht", "svg", "mht", "mhtml", "shtml",
        )
        for (ext in extensions) {
            val warnings = AttachmentSafety.check("file.${ext.uppercase()}", "application/x-unknown-type", bytes("")).warnings
            assertEquals(true, DANGEROUS_EXTENSION in warnings, ext)
        }
        assertEquals(setOf(DANGEROUS_EXTENSION), AttachmentSafety.check("setup.exe. ", "application/x-unknown-type", bytes("")).warnings)
        assertEquals(emptySet(), AttachmentSafety.check("photo.jpeg", "image/jpeg", jpeg).warnings)
        assertEquals(emptySet(), AttachmentSafety.check("exe", "application/x-unknown-type", bytes("")).warnings)
    }

    @Test
    fun doubleExtensionsAndHiddenCharacters() {
        assertEquals(
            setOf(DOUBLE_EXTENSION, DANGEROUS_EXTENSION),
            AttachmentSafety.check("invoice.pdf.exe", "application/x-unknown-type", bytes("")).warnings,
        )
        assertEquals(
            setOf(DOUBLE_EXTENSION, DANGEROUS_EXTENSION),
            AttachmentSafety.check("invoice.pdf .html", "application/x-unknown-type", bytes("")).warnings,
        )
        assertEquals(emptySet(), AttachmentSafety.check("backup.tar.gz", "application/gzip", bytes("\u001F\u008B")).warnings)
        assertEquals(emptySet(), AttachmentSafety.check("report.2026.pdf", "application/pdf", pdf).warnings)
        // "invoice<RLO>fdp.exe" reads as "invoiceexe.pdf".
        assertEquals(
            setOf(HIDDEN_CHARACTERS, DANGEROUS_EXTENSION),
            AttachmentSafety.check("invoice\u202Efdp.exe", "application/x-unknown-type", bytes("")).warnings,
        )
        assertEquals(setOf(HIDDEN_CHARACTERS), AttachmentSafety.check("a\u2067b.pdf", "application/pdf", pdf).warnings)
    }
}
