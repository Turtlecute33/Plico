package app.plico.ui.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plico.R
import app.plico.data.Account
import app.plico.data.Draft
import app.plico.data.DraftState
import app.plico.data.MessageSummary
import app.plico.html.rememberSenderLogo
import app.plico.mail.mime.Addresses
import app.plico.ui.components.Avatar
import app.plico.ui.components.ConfirmDialog
import app.plico.ui.components.Icons
import app.plico.ui.message.revealBidi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The swipe actions of one row; null turns a direction off. */
@Immutable
internal data class RowSwipe(val start: RowAction?, val end: RowAction?)

// A removed row normally leaves the list at once; if it stays (archive in Starred), it slides back.
private const val REMOVE_GRACE_MS = 700L

/**
 * One message of the list (DESIGN §13) with swipe actions (apis AF4): the state is remembered per
 * message id (not saveable, so that an undone row starts fresh), `onDismiss` captures only the id,
 * and toggles reset the row before they change the message. [account] is set in lists of several
 * accounts: its colour goes on the account dot when account dots are on ([look]).
 * A [sent] row shows its first recipient instead of the sender. [logoAllowed]: the row may show the
 * sender's logo when logos are on (DESIGN §12).
 */
@Composable
internal fun MessageRow(
    row: MessageSummary,
    swipe: RowSwipe,
    date: String,
    selected: Boolean,
    selecting: Boolean,
    account: Account?,
    draft: Boolean,
    sent: Boolean,
    look: RowLook,
    logoAllowed: Boolean,
    onOpen: (MessageSummary) -> Unit,
    onSelect: (Long) -> Unit,
    onAction: (Long, RowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val id = row.id
    val state = remember(id) { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled) { it * 0.4f } }
    val scope = rememberCoroutineScope()
    val currentSwipe by rememberUpdatedState(swipe)
    val currentAction by rememberUpdatedState(onAction)
    // One tick each time the swipe passes the threshold; the system touch-feedback setting applies (PLICO-UX-011).
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state) {
        snapshotFlow { state.targetValue }.drop(1).collect {
            if (it != SwipeToDismissBoxValue.Settled) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    val onDismiss = remember(id) {
        { value: SwipeToDismissBoxValue ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentSwipe.start
                SwipeToDismissBoxValue.EndToStart -> currentSwipe.end
                SwipeToDismissBoxValue.Settled -> null
            }
            scope.launch {
                if (action == null || !action.removesRow) {
                    state.reset()
                    action?.let { currentAction(id, it) }
                } else {
                    currentAction(id, action)
                    delay(REMOVE_GRACE_MS)
                    state.reset()
                }
            }
            Unit
        }
    }
    SwipeToDismissBox(
        state = state,
        backgroundContent = { SwipeBackground(state.dismissDirection, swipe) },
        modifier = modifier,
        enableDismissFromStartToEnd = swipe.start != null,
        enableDismissFromEndToStart = swipe.end != null,
        gesturesEnabled = !selecting,
        onDismiss = onDismiss,
    ) {
        RowContent(row, swipe, date, selected, selecting, account, draft, sent, look, logoAllowed, onOpen, onSelect, onAction)
    }
}

@Composable
private fun RowScope.SwipeBackground(direction: SwipeToDismissBoxValue, swipe: RowSwipe) {
    val action = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> swipe.start
        SwipeToDismissBoxValue.EndToStart -> swipe.end
        SwipeToDismissBoxValue.Settled -> null
    } ?: return
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (action) {
        RowAction.DELETE, RowAction.DELETE_FOREVER, RowAction.SPAM -> scheme.errorContainer to scheme.onErrorContainer
        RowAction.ARCHIVE, RowAction.NOT_SPAM -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        RowAction.STAR, RowAction.UNSTAR -> scheme.primaryContainer to scheme.onPrimaryContainer
        RowAction.MARK_READ, RowAction.MARK_UNREAD -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    val fromStart = direction == SwipeToDismissBoxValue.StartToEnd
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(container)
            .padding(horizontal = 24.dp),
        horizontalArrangement = if (fromStart) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = @Composable {
            Text(stringResource(actionLabel(action)), color = content, style = MaterialTheme.typography.labelLarge)
        }
        if (!fromStart) {
            label()
            Spacer(Modifier.width(12.dp))
        }
        Icon(actionIcon(action), contentDescription = null, tint = content)
        if (fromStart) {
            Spacer(Modifier.width(12.dp))
            label()
        }
    }
}

@Composable
private fun RowContent(
    row: MessageSummary,
    swipe: RowSwipe,
    date: String,
    selected: Boolean,
    selecting: Boolean,
    account: Account?,
    draft: Boolean,
    sent: Boolean,
    look: RowLook,
    logoAllowed: Boolean,
    onOpen: (MessageSummary) -> Unit,
    onSelect: (Long) -> Unit,
    onAction: (Long, RowAction) -> Unit,
) {
    val recipient = remember(row.toList, sent) { if (sent) firstRecipient(row.toList)?.let(::revealBidi) else null }
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val unread = !row.seen
    val emphasis = rowEmphasis(look, row.seen)
    val weight = if (emphasis.bold) FontWeight.Bold else FontWeight.Normal
    val textColor = if (emphasis.dim) scheme.onSurfaceVariant else scheme.onSurface
    val avatar = remember(look, row.seen, row.fromEmail, row.toList, sent, draft, account?.color) {
        avatarStyle(look, row.seen, senderKey(row, sent, draft), account?.color)
    }
    // The logo of From only; the gate in ListContext keeps it off for sent mail, drafts and Spam.
    val logo = rememberSenderLogo(row.fromEmail, look.logos, logoAllowed, before = row.date, exceptId = row.id)
    // In lists of several accounts a colour shows the account; TalkBack says its name (WCAG 1.4.1).
    val stateText = listOfNotNull(stringResource(R.string.mailbox_unread).takeIf { unread }, account?.label)
        .joinToString(", ")
    val selectLabel = stringResource(if (selected) R.string.mailbox_deselect else R.string.mailbox_select)
    // Swipes are gestures only; TalkBack users get the same actions here.
    val a11yActions = listOfNotNull(swipe.start, swipe.end).distinct().map { action ->
        CustomAccessibilityAction(stringResource(actionLabel(action))) {
            onAction(row.id, action)
            true
        }
    }
    Surface(color = if (selected) scheme.secondaryContainer else scheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = if (selecting) selectLabel else stringResource(R.string.mailbox_open),
                    onLongClickLabel = selectLabel,
                    onLongClick = { onSelect(row.id) },
                    onClick = { if (selecting) onSelect(row.id) else onOpen(row) },
                )
                .semantics {
                    if (selecting) this.selected = selected
                    if (stateText.isNotEmpty()) stateDescription = stateText
                    customActions = a11yActions
                }
                .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 8.dp),
        ) {
            Box(Modifier.padding(top = 2.dp)) {
                if (selected) {
                    SelectedMark()
                } else {
                    Avatar(
                        name = recipient ?: row.fromName.ifBlank { row.fromEmail },
                        colorIndex = avatar.colorIndex,
                        logo = logo,
                        badge = avatar.badge,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                    Text(
                        text = when {
                            draft -> stringResource(R.string.mailbox_draft)
                            recipient != null -> stringResource(R.string.mailbox_draft_to, recipient)
                            else -> revealBidi(row.fromName).ifBlank { row.fromEmail }.ifBlank { stringResource(R.string.ui_no_sender) }
                        },
                        color = if (draft) scheme.error else textColor,
                        style = type.bodyLarge,
                        fontWeight = weight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.attachments) {
                        Icon(
                            imageVector = Icons.Attach,
                            contentDescription = stringResource(R.string.mailbox_has_attachments),
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(16.dp),
                        )
                    }
                    if (emphasis.dot) {
                        // Decorative: TalkBack already says "Unread".
                        Box(
                            Modifier
                                .padding(start = 8.dp)
                                .size(UNREAD_DOT)
                                .background(scheme.primary, CircleShape),
                        )
                    }
                    Text(
                        text = date,
                        color = if (emphasis.bold) scheme.primary else scheme.onSurfaceVariant,
                        style = type.labelMedium,
                        fontWeight = weight,
                        maxLines = 1,
                        modifier = Modifier.padding(start = if (emphasis.dot) 6.dp else 8.dp),
                    )
                }
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = revealBidi(row.subject).ifBlank { stringResource(R.string.ui_no_subject) },
                            color = textColor,
                            style = type.bodyMedium,
                            fontWeight = if (emphasis.bold) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (row.snippet.isNotBlank()) {
                            Text(
                                text = row.snippet,
                                color = scheme.onSurfaceVariant,
                                style = type.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconToggleButton(
                        checked = row.flagged,
                        onCheckedChange = { onAction(row.id, if (it) RowAction.STAR else RowAction.UNSTAR) },
                    ) {
                        Icon(
                            imageVector = if (row.flagged) Icons.StarFilled else Icons.Star,
                            contentDescription = stringResource(R.string.mailbox_action_star),
                            tint = if (row.flagged) scheme.tertiary else scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The avatar of a selected row: a check in a filled circle. */
@Composable
private fun SelectedMark() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/**
 * A local draft on top of the Drafts view (DESIGN §7.5): its state, the error of a failed send and
 * Retry. A draft that is being sent cannot be opened.
 */
@Composable
internal fun LocalDraftRow(
    draft: Draft,
    date: String,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val recipients = remember(draft.toList) {
        runCatching { Addresses.parseList(draft.toList) }.getOrDefault(emptyList())
            .joinToString(", ") { it.name?.takeIf(String::isNotBlank) ?: it.email }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = draft.state != DraftState.SENDING,
                onClickLabel = stringResource(R.string.mailbox_open),
                onClick = onOpen,
            )
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
    ) {
        Avatar(recipients, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DraftStateLabel(draft.state)
                Text(
                    text = if (recipients.isEmpty()) {
                        stringResource(R.string.mailbox_draft_no_recipients)
                    } else {
                        stringResource(R.string.mailbox_draft_to, recipients)
                    },
                    style = type.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                Text(
                    text = date,
                    style = type.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = draft.subject.ifBlank { stringResource(R.string.ui_no_subject) },
                style = type.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (draft.body.isNotBlank()) {
                Text(
                    text = draft.body.trim(),
                    style = type.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (DraftState.failed(draft.state)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = draft.error.orEmpty(),
                        style = type.bodySmall,
                        color = scheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.mailbox_retry_send)) }
                }
            }
        }
    }
}

/** A small tonal label with the state of a local draft. */
@Composable
private fun DraftStateLabel(state: Int) {
    val scheme = MaterialTheme.colorScheme
    val (text, container, content) = when (state) {
        DraftState.QUEUED -> Triple(R.string.mailbox_draft_queued, scheme.secondaryContainer, scheme.onSecondaryContainer)
        DraftState.SENDING -> Triple(R.string.mailbox_draft_sending, scheme.secondaryContainer, scheme.onSecondaryContainer)
        DraftState.FAILED, DraftState.UNCERTAIN ->
            Triple(R.string.mailbox_draft_failed, scheme.errorContainer, scheme.onErrorContainer)
        else -> Triple(R.string.mailbox_draft, Color.Transparent, scheme.error)
    }
    Text(
        text = stringResource(text),
        color = content,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = if (container == Color.Transparent) 0.dp else 8.dp, vertical = 2.dp),
    )
}

/** The name, else the address, of the first recipient in a stored `to_list`; null when there is none. */
internal fun firstRecipient(toList: String): String? =
    Addresses.parseList(toList).firstOrNull()?.let { it.name?.takeIf(String::isNotBlank) ?: it.email }

/** The address of the first recipient in a stored `to_list`; null when there is none. */
internal fun firstRecipientAddress(toList: String): String? =
    Addresses.parseList(toList).firstOrNull()?.email?.takeIf(String::isNotBlank)

/** The unread dot before the date (unread style on). */
private val UNREAD_DOT = 8.dp

/** True when a failed send may have gone out (DESIGN §7.5): Retry asks first. */
internal val Draft.mayHaveBeenSent: Boolean get() = state == DraftState.UNCERTAIN

/** "Send again?" before Retry of a send that may have gone out; the text is the draft's error. */
@Composable
internal fun SendAgainDialog(draft: Draft, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.mailbox_send_again_title),
        text = draft.error,
        confirmLabel = stringResource(R.string.mailbox_send_again),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
