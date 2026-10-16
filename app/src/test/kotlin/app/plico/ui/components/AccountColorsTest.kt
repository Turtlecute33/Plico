package app.plico.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountColorsTest {
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    @Test
    fun textOnEveryEntryIsReadable() {
        for (dark in listOf(false, true)) {
            for (i in 0 until AccountColors.COUNT) {
                val c = AccountColors.of(i, dark)
                assertTrue(contrast(c.color, c.onColor) >= 4.5f, "entry $i dark=$dark")
            }
        }
    }

    @Test
    fun everyEntryStandsOutFromTheSurface() {
        val lightSurface = Color(0xFFFBF8FF)
        val darkSurface = Color(0xFF121318)
        for (i in 0 until AccountColors.COUNT) {
            assertTrue(contrast(AccountColors.of(i, dark = false).color, lightSurface) >= 3f, "light $i")
            assertTrue(contrast(AccountColors.of(i, dark = true).color, darkSurface) >= 3f, "dark $i")
        }
    }

    @Test
    fun entriesAreDistinct() {
        assertEquals(AccountColors.COUNT, (0 until AccountColors.COUNT).map { AccountColors.of(it, false) }.toSet().size)
        assertEquals(AccountColors.COUNT, (0 until AccountColors.COUNT).map { AccountColors.of(it, true) }.toSet().size)
    }

    @Test
    fun indexesWrapAround() {
        assertEquals(AccountColors.of(0, false), AccountColors.of(AccountColors.COUNT, false))
        assertEquals(AccountColors.of(AccountColors.COUNT - 1, true), AccountColors.of(-1, true))
    }

    @Test
    fun nextTakesTheFirstFreeEntry() {
        assertEquals(0, AccountColors.next(emptyList()))
        assertEquals(2, AccountColors.next(listOf(0, 1)))
        assertEquals(0, AccountColors.next(listOf(1, 3)))
    }

    @Test
    fun nextStartsAgainWhenAllAreTaken() {
        val all = (0 until AccountColors.COUNT).toList()
        assertEquals(0, AccountColors.next(all))
        assertEquals(1, AccountColors.next(all + 0))
    }
}
