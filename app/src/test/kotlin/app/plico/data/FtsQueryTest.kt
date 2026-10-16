package app.plico.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FtsQueryTest {
    private fun q(text: String) = MessageDao.ftsQuery(text)

    @Test
    fun wordsBecomePrefixPhrases() {
        assertEquals("\"invoice*\"", q("invoice"))
        assertEquals("\"hello*\" \"wor*\"", q("  hello   wor "))
    }

    @Test
    fun punctuationSplitsInsideOnePhrase() {
        assertEquals("\"anna example org*\"", q("anna@example.org"))
        assertEquals("\"re invoice*\"", q("re:invoice"))
    }

    @Test
    fun operatorsAndQuotesCannotPass() {
        assertEquals("\"a*\" \"b*\"", q("\"a\" \"b"))
        assertEquals("\"x*\" \"y*\"", q("x* -y"))
        assertEquals("\"subject body*\"", q("subject:body"))
        assertEquals("\"NEAR*\" \"OR*\" \"AND*\" \"NOT*\"", q("NEAR OR AND NOT"))
        assertEquals("\"a b*\"", q("(a)^(b)"))
    }

    @Test
    fun keepsNonAsciiLettersAndDigits() {
        assertEquals("\"Zürich*\" \"2026*\"", q("Zürich 2026"))
        assertEquals("\"日本語*\"", q("日本語"))
        assertEquals("\"café*\"", q("café"))
    }

    @Test
    fun nothingSearchable() {
        assertNull(q(""))
        assertNull(q("   "))
        assertNull(q("\"*-()\" :"))
    }
}
