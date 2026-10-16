package app.plico.ui.mailbox

import androidx.compose.ui.state.ToggleableState
import app.plico.data.AccountAbilities
import app.plico.data.Folder
import app.plico.data.FolderRole
import app.plico.data.MessageSummary
import app.plico.data.Prefs

/** Where a message lives, as far as the list actions care. */
internal enum class Place { NORMAL, ARCHIVE, DRAFTS, TRASH, SPAM }

/** A change the user can make to messages from the list. */
internal enum class RowAction(val removesRow: Boolean = false) {
    ARCHIVE(removesRow = true),
    DELETE(removesRow = true),

    /** Inside Trash or Spam; the UI confirms first. */
    DELETE_FOREVER,
    MARK_READ,
    MARK_UNREAD,
    STAR,
    UNSTAR,
    SPAM(removesRow = true),
    NOT_SPAM(removesRow = true),
}

/**
 * One list row with what the actions need to know about it. Gmail Archive removes `\Inbox`, so [canArchive] is
 * off for Gmail rows outside the inbox views, where a row may not have it. [sent]: mail the user sent (in Sent, or
 * from the account's address); the row shows its recipient.
 */
internal data class RowInfo(
    val id: Long,
    val accountId: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val place: Place,
    val gmail: Boolean,
    val abilities: AccountAbilities = AccountAbilities(),
    val canArchive: Boolean = true,
    val sent: Boolean = false,
) {
    val archivable: Boolean get() = place == Place.NORMAL && canArchive
}

/** The actions of the multi-select bar for the selected rows; null or false hides an action. */
internal data class SelectionActions(
    val archive: Boolean,
    /** [RowAction.DELETE] or, when every row is in Trash or Spam, [RowAction.DELETE_FOREVER]. */
    val delete: RowAction?,
    /** [RowAction.MARK_READ] when any row is unread, else [RowAction.MARK_UNREAD]. */
    val read: RowAction,
    /** [RowAction.STAR] when any row is not starred, else [RowAction.UNSTAR]. */
    val star: RowAction,
    /** [RowAction.SPAM] or [RowAction.NOT_SPAM]. */
    val spam: RowAction?,
    /** The account whose folders the Move picker offers; null when the rows belong to several accounts. */
    val moveAccount: Long?,
    /** Gmail label picker. */
    val labels: Boolean,
)

/** Swipe mapping, selection rules and paging of the message list. Pure, unit-tested. */
internal object RowActions {
    /** Rows loaded per page; the list asks for the next page near its end. */
    const val PAGE = 50
    private const val PREFETCH = 15

    /**
     * Where [row] lives: by its source mailbox [source] (Trash, Spam, Drafts, the Riseup archive with id
     * [archiveId]) and its draft flag.
     */
    fun placeOf(row: MessageSummary, source: Folder?, archiveId: Long?): Place = when {
        source?.role == FolderRole.TRASH -> Place.TRASH
        source?.role == FolderRole.JUNK -> Place.SPAM
        row.draft || source?.role == FolderRole.DRAFTS -> Place.DRAFTS
        row.folderId == archiveId || source?.role == FolderRole.ARCHIVE -> Place.ARCHIVE
        else -> Place.NORMAL
    }

    /** The action of a swipe set to [choice] on [row]; null turns that swipe direction off. */
    fun swipe(choice: Prefs.Swipe, row: RowInfo): RowAction? = when (choice) {
        Prefs.Swipe.ARCHIVE -> RowAction.ARCHIVE.takeIf { row.archivable }
        Prefs.Swipe.DELETE -> when (row.place) {
            Place.TRASH, Place.SPAM -> RowAction.DELETE_FOREVER
            else -> RowAction.DELETE.takeIf { row.abilities.canDelete }
        }
        Prefs.Swipe.READ -> if (row.seen) RowAction.MARK_UNREAD else RowAction.MARK_READ
        Prefs.Swipe.STAR -> if (row.flagged) RowAction.UNSTAR else RowAction.STAR
        Prefs.Swipe.NONE -> null
    }

    /** The multi-select actions for [rows] (at least one). */
    fun selection(rows: List<RowInfo>): SelectionActions {
        val accounts = rows.mapTo(HashSet()) { it.accountId }
        val moveAccount = accounts.singleOrNull()
        val inBin = rows.all { it.place == Place.TRASH || it.place == Place.SPAM }
        val delete = when {
            inBin -> RowAction.DELETE_FOREVER
            rows.none { it.place == Place.TRASH || it.place == Place.SPAM } && rows.all { it.abilities.canDelete } ->
                RowAction.DELETE
            else -> null
        }
        val spam = when {
            rows.all { it.place == Place.SPAM } -> RowAction.NOT_SPAM
            rows.none { it.place == Place.SPAM || it.place == Place.DRAFTS } && rows.all { it.abilities.canSpam } ->
                RowAction.SPAM
            else -> null
        }
        return SelectionActions(
            archive = rows.all { it.archivable },
            delete = delete,
            read = if (rows.any { !it.seen }) RowAction.MARK_READ else RowAction.MARK_UNREAD,
            star = if (rows.any { !it.flagged }) RowAction.STAR else RowAction.UNSTAR,
            spam = spam,
            moveAccount = moveAccount,
            labels = moveAccount != null && rows.all { it.gmail && it.place != Place.TRASH && it.place != Place.SPAM },
        )
    }

    /** [selected] without the ids that left the list ([visible]). */
    fun prune(selected: Set<Long>, visible: Collection<Long>): Set<Long> =
        if (selected.isEmpty()) selected else selected.intersect(visible.toSet())

    /** [selected] with [id] added or removed. */
    fun toggle(selected: Set<Long>, id: Long): Set<Long> = if (id in selected) selected - id else selected + id

    /** The list limit after the user scrolled to [lastVisible]: one page more near the end of a full page. */
    fun nextLimit(limit: Int, loaded: Int, lastVisible: Int): Int =
        if (loaded >= limit && lastVisible >= loaded - PREFETCH) limit + PAGE else limit

    /**
     * Label changes from the picker: [initial] holds the labels that every message has ([ToggleableState.On])
     * or some have ([ToggleableState.Indeterminate]); [final] the states the user left. Returns (add, remove).
     */
    fun labelChanges(
        initial: Map<Long, ToggleableState>,
        final: Map<Long, ToggleableState>,
    ): Pair<Set<Long>, Set<Long>> {
        val add = final.filter { (id, state) -> state == ToggleableState.On && initial[id] != ToggleableState.On }.keys
        val remove = final.filter { (id, state) ->
            state == ToggleableState.Off && (initial[id] ?: ToggleableState.Off) != ToggleableState.Off
        }.keys
        return add to remove
    }

    /** The next state of a label checkbox: a mixed label becomes set, then it toggles. */
    fun nextLabelState(state: ToggleableState): ToggleableState =
        if (state == ToggleableState.On) ToggleableState.Off else ToggleableState.On

    /** Initial label states for messages with these label memberships (message id to label ids). */
    fun labelStates(memberships: Map<Long, Set<Long>>): Map<Long, ToggleableState> {
        if (memberships.isEmpty()) return emptyMap()
        val counts = HashMap<Long, Int>()
        memberships.values.forEach { labels -> labels.forEach { counts.merge(it, 1, Int::plus) } }
        return counts.mapValues { (_, n) -> if (n == memberships.size) ToggleableState.On else ToggleableState.Indeterminate }
    }
}
