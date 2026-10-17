package app.plico.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.plico.R
import app.plico.app
import app.plico.data.Prefs
import app.plico.html.SenderLogos
import app.plico.ui.components.rememberPref
import kotlin.concurrent.thread

/**
 * Settings group "Message list" (DESIGN §13): the unread style, sender colours for unread mail, and sender
 * logos with their source (§12). Logos are off by default; turning them off or changing the source deletes the
 * cached logos.
 */
@Composable
fun ListLookGroup() {
    val app = LocalContext.current.app
    val prefs = app.prefs
    val markUnread by rememberPref(prefs.markUnread) { markUnread }
    val avatarColors by rememberPref(prefs.avatarColors) { avatarColors }
    val logos by rememberPref(prefs.senderLogos) { senderLogos }
    val source by rememberPref(prefs.logoSource) { logoSource }
    var choosingSource by rememberSaveable { mutableStateOf(false) }

    SettingsGroup(stringResource(R.string.settings_list)) {
        switch(R.string.settings_mark_unread, R.string.settings_mark_unread_text, markUnread) { prefs.markUnread = it }
        switch(R.string.settings_avatar_unread, R.string.settings_avatar_unread_text, avatarColors) {
            prefs.avatarColors = it
        }
        switch(R.string.settings_logos, R.string.settings_logos_text, logos) { on ->
            prefs.senderLogos = on
            if (!on) app.logos.clearInBackground()
        }
        item {
            ClickRow(
                shapes = it,
                title = stringResource(R.string.settings_logo_source),
                onClick = { choosingSource = true },
                summary = stringResource(source.logoTitle()),
                enabled = logos,
            )
        }
        if (logos) item { NoteRow(it, stringResource(R.string.settings_logos_note)) }
    }

    if (choosingSource && logos) {
        ChoiceDialog(
            title = stringResource(R.string.settings_logo_source),
            choices = Prefs.LogoSource.entries.map { Choice(it, stringResource(it.logoTitle()), stringResource(it.logoDescription())) },
            selected = source,
            onSelect = { picked ->
                if (picked != prefs.logoSource) {
                    prefs.logoSource = picked
                    // Logos of the old source must not stay.
                    app.logos.clearInBackground()
                }
            },
            onDismiss = { choosingSource = false },
        )
    }
}

/** [SenderLogos.clear] off the main thread; settings call it without a scope that a closed screen would cancel. */
private fun SenderLogos.clearInBackground() {
    thread(name = "logo-clear") { clear() }
}

@StringRes
private fun Prefs.LogoSource.logoTitle(): Int = when (this) {
    Prefs.LogoSource.DUCKDUCKGO -> R.string.settings_logo_ddg
    Prefs.LogoSource.WEBSITE -> R.string.settings_logo_website
    Prefs.LogoSource.GOOGLE -> R.string.settings_logo_google
}

@StringRes
private fun Prefs.LogoSource.logoDescription(): Int = when (this) {
    Prefs.LogoSource.DUCKDUCKGO -> R.string.settings_logo_ddg_text
    Prefs.LogoSource.WEBSITE -> R.string.settings_logo_website_text
    Prefs.LogoSource.GOOGLE -> R.string.settings_logo_google_text
}
