package app.plico.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.plico.ui.theme.isDark

/** A colour to fill with and the colour of text or icons on it. */
@Immutable
data class AccountColor(val color: Color, val onColor: Color)

/**
 * The account palette (DESIGN §13). `Account.color` is an index into it. Every entry has a light-theme
 * variant (HCT tone 40, white on it) and a dark-theme variant (tone 80, tone 20 on it), so text on
 * it and the colour on the surface stay readable in both themes.
 */
object AccountColors {
    const val COUNT = 8

    private val light = listOf(
        0xFF4359A9, 0xFF006A68, 0xFF944B00, 0xFF9C4141, 0xFF346A22, 0xFF6E4DA1, 0xFF006590, 0xFF795900,
    ).map { AccountColor(Color(it), Color.White) }

    private val dark = listOf(
        0xFFB7C4FF to 0xFF192C68, // indigo
        0xFF72D6D4 to 0xFF003736, // teal
        0xFFFFB783 to 0xFF4F2500, // orange
        0xFFFFB3B0 to 0xFF5A1A1C, // red
        0xFFA3D48D to 0xFF0F3903, // green
        0xFFD6BAFF to 0xFF3C2361, // violet
        0xFF8ACEFD to 0xFF00344D, // blue
        0xFFEDC061 to 0xFF402D00, // amber
    ).map { (color, on) -> AccountColor(Color(color), Color(on)) }

    /** Palette entry [index] (any value; it wraps around) for a light or dark theme. */
    fun of(index: Int, dark: Boolean): AccountColor = (if (dark) this.dark else light)[Math.floorMod(index, COUNT)]

    /** Palette entry [index] for the current theme. */
    @Composable
    @ReadOnlyComposable
    fun of(index: Int): AccountColor = of(index, MaterialTheme.colorScheme.isDark)

    /** The first index that no account in [used] has; when all are taken, the palette starts again. */
    fun next(used: Collection<Int>): Int = (0 until COUNT).firstOrNull { it !in used } ?: (used.size % COUNT)
}
