package app.plico.ui.message

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Icons only the message screen uses; Material Symbols Rounded, weight 400, like [app.plico.ui.components.Icons]. */
internal object MessageIcons {
    val ExpandMore: ImageVector by lazy {
        ImageVector.Builder(
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f,
        ).addGroup(translationY = 960f)
            .addPath(
                addPathNodes(
                    "M480-361q-8 0-15-2.5t-13-8.5L268-556q-11-11-11-28t11-28q11-11 28-11t28 11l156 156 " +
                        "156-156q11-11 28-11t28 11q11 11 11 28t-11 28L508-372q-6 6-13 8.5t-15 2.5Z",
                ),
                fill = SolidColor(Color.Black),
            )
            .clearGroup()
            .build()
    }
}
