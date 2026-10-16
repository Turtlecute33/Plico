package app.plico.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvatarTest {
    @Test
    fun firstLetterUpperCase() {
        assertEquals("A", initialOf("alice"))
        assertEquals("Ö", initialOf("örjan"))
        assertEquals("日", initialOf("日本"))
    }

    @Test
    fun skipsPunctuationSpacesAndEmoji() {
        assertEquals("B", initialOf("  \"bob\""))
        assertEquals("E", initialOf("😀 eve"))
        assertEquals("C", initialOf("<carol@example.org>"))
    }

    @Test
    fun digitsCount() {
        assertEquals("1", initialOf("1password"))
    }

    @Test
    fun lettersOutsideTheBasicPlane() {
        // DESERET SMALL LETTER LONG I (U+10428) upper-cases to U+10400.
        assertEquals("𐐀", initialOf("𐐨x"))
    }

    @Test
    fun nothingToShow() {
        assertNull(initialOf(""))
        assertNull(initialOf("!?. 😀"))
    }
}
