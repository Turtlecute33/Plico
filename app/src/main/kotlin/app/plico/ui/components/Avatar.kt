package app.plico.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A round avatar with the first letter of [name] (DESIGN §13; never a fetched picture, §12).
 * [colorIndex] picks an [AccountColors] entry (unified view); null uses a neutral theme colour.
 * Decorative: the name is always shown next to it.
 */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, colorIndex: Int? = null, size: Dp = 40.dp) {
    val colors = colorIndex?.let { AccountColors.of(it) }
        ?: AccountColor(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    Box(
        modifier = modifier
            .size(size)
            .background(colors.color, CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val initial = initialOf(name)
        if (initial == null) {
            Icon(Icons.Account, contentDescription = null, tint = colors.onColor, modifier = Modifier.size(size * 0.6f))
        } else {
            Text(
                text = initial,
                color = colors.onColor,
                // Sized from the circle, not the font scale, so that the letter always fits.
                fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** The first letter or digit of [name], upper-cased; null when there is none. Handles surrogate pairs. */
fun initialOf(name: String): String? {
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        if (Character.isLetterOrDigit(cp)) return String(Character.toChars(Character.toUpperCase(cp)))
        i += Character.charCount(cp)
    }
    return null
}
