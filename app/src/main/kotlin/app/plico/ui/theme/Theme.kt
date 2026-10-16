package app.plico.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Plico's Material 3 expressive theme (DESIGN §13): the wallpaper colours of the phone (dynamic colour, always
 * there from Android 12, minSdk 35), day/night, expressive motion.
 */
@Composable
fun PlicoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colorScheme = remember(context, dark) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = PlicoShapes,
        typography = PlicoTypography,
        content = content,
    )
}

/** True when this colour scheme is dark (follows the theme in use, not only the system setting). */
val ColorScheme.isDark: Boolean get() = surface.luminance() < 0.5f

// Slightly softer corners than the baseline; the expressive steps keep their order.
private val PlicoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    largeIncreased = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

// The system font (no downloadable fonts, DESIGN §12) with tighter tracking for large text and
// medium weight for headings, which reads cleaner in dense mail lists.
private val PlicoTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(letterSpacing = (-0.5).sp),
        displayMedium = displayMedium.copy(letterSpacing = (-0.25).sp),
        displaySmall = displaySmall.copy(letterSpacing = (-0.25).sp),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Medium),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium),
    )
}
