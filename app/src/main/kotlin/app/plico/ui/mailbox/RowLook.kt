package app.plico.ui.mailbox

import androidx.compose.runtime.Immutable
import app.plico.data.Prefs

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
 * drafts); [accountColor] is set only in lists of more than one account. The avatar has the account colour.
 */
fun avatarStyle(look: RowLook, seen: Boolean, senderKey: String?, accountColor: Int?): AvatarStyle =
    AvatarStyle(colorIndex = accountColor, badge = null)
