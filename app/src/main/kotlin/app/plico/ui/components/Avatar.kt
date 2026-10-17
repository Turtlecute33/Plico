package app.plico.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A round avatar with the first letter of [name] (DESIGN §13), or a [logo]: a fetched logo only when the user
 * turned on sender logos (§12). [colorIndex] picks an [AccountColors] entry (account or sender colour); null uses
 * a neutral theme colour. With a logo the circle keeps its colour as a ring around a white disc, so that the read
 * state stays visible. [badge] adds a small dot in that [AccountColors] entry at the bottom end (the account in
 * lists of several accounts). Decorative: the name is always shown next to it.
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    colorIndex: Int? = null,
    size: Dp = 40.dp,
    logo: ImageBitmap? = null,
    badge: Int? = null,
) {
    val colors = colorIndex?.let { AccountColors.of(it) }
        ?: AccountColor(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    Box(modifier = modifier.size(size).clearAndSetSemantics {}) {
        Box(
            modifier = Modifier
                .size(size)
                .background(colors.color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                Box(
                    modifier = Modifier
                        .size(size - LOGO_RING * 2)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .padding(LOGO_INSET)
                            .clip(CircleShape),
                    )
                }
            } else {
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
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(BADGE_SIZE)
                    .background(AccountColors.of(badge).color, CircleShape)
                    .border(BADGE_BORDER, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

/** The coloured ring around a logo, the space between the white disc and the logo, and the account dot. */
private val LOGO_RING = 3.dp
private val LOGO_INSET = 4.dp
private val BADGE_SIZE = 12.dp
private val BADGE_BORDER = 2.dp

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
