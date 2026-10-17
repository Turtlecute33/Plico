package app.plico.ui.mailbox

import androidx.compose.runtime.Immutable
import app.plico.data.MessageSummary
import app.plico.data.Prefs
import app.plico.ui.components.SenderColors
import java.util.Locale

/** How the message rows look; read once from the settings for the whole list. [logos] null = logos off. */
@Immutable
data class RowLook(val markUnread: Boolean, val avatarColors: Boolean, val logos: Prefs.LogoSource?)

/**
 * The colours of a row's avatar. [colorIndex] is an AccountColors index; null = the neutral theme colour.
 * [badge] is the AccountColors index of the account dot; null = no dot.
 */
data class AvatarStyle(val colorIndex: Int?, val badge: Int?)

/**
 * The avatar style of a row. [senderKey] is the lower-case address that picks the sender colour (null for
 * drafts); [accountColor] is set only in lists of more than one account.
 *
 * Sender colours off: the avatar has the account colour (or the neutral colour), as before. On: an unread row
 * gets the colour of its sender and a read row the neutral colour, so that the colour shows the read state; the
 * account colour moves to a small dot.
 */
fun avatarStyle(look: RowLook, seen: Boolean, senderKey: String?, accountColor: Int?): AvatarStyle =
    if (!look.avatarColors) {
        AvatarStyle(colorIndex = accountColor, badge = null)
    } else {
        AvatarStyle(colorIndex = senderKey?.takeIf { !seen }?.let(SenderColors::index), badge = accountColor)
    }

/**
 * The text emphasis of a row. [bold]: unread weights (sender bold, subject semi-bold, date bold in the primary
 * colour). [dim]: sender, subject and date in the quieter colour (read rows with the unread style on). [dot]: the
 * unread dot before the date.
 */
data class RowEmphasis(val bold: Boolean, val dim: Boolean, val dot: Boolean)

/** The text emphasis of a row; with the unread style off it is the look from before the setting. */
fun rowEmphasis(look: RowLook, seen: Boolean): RowEmphasis =
    RowEmphasis(bold = !seen, dim = look.markUnread && seen, dot = look.markUnread && !seen)

/**
 * The address that picks the sender colour of [row] (used only on the phone): the lower-case sender, or the first
 * recipient for mail the user [sent]; null for a [draft] or when there is no address.
 */
fun senderKey(row: MessageSummary, sent: Boolean, draft: Boolean): String? = when {
    draft -> null
    sent -> firstRecipientAddress(row.toList)
    else -> row.fromEmail
}?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }
