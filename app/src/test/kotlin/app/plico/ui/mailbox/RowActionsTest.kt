package app.plico.ui.mailbox

import androidx.compose.ui.state.ToggleableState
import app.plico.data.FolderRole
import app.plico.data.Prefs.Swipe
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RowActionsTest {
    private fun row(
        place: Place = Place.NORMAL,
        id: Long = 1,
        accountId: Long = 1,
        seen: Boolean = true,
        flagged: Boolean = false,
        gmail: Boolean = false,
        abilities: AccountAbilities = AccountAbilities(),
    ) = RowInfo(id, accountId, seen, flagged, place, gmail, abilities)

    @Test
    fun placeComesFromTheSourceMailboxAndTheDraftFlag() {
        val inbox = folder(1, 1, "INBOX", FolderRole.INBOX)
        assertEquals(Place.NORMAL, RowActions.placeOf(summary(1, 1), inbox, archiveId = null))
        assertEquals(Place.TRASH, RowActions.placeOf(summary(1, 2), folder(2, 1, "Trash", FolderRole.TRASH), null))
        assertEquals(Place.SPAM, RowActions.placeOf(summary(1, 3), folder(3, 1, "Spam", FolderRole.JUNK), null))
        assertEquals(Place.DRAFTS, RowActions.placeOf(summary(1, 4), folder(4, 1, "Drafts", FolderRole.DRAFTS), null))
        assertEquals(Place.DRAFTS, RowActions.placeOf(summary(1, 1, draft = true), inbox, null))
        assertEquals(Place.ARCHIVE, RowActions.placeOf(summary(1, 5), folder(5, 1, "Archives"), archiveId = 5))
        assertEquals(Place.ARCHIVE, RowActions.placeOf(summary(1, 6), folder(6, 1, "Archive", FolderRole.ARCHIVE), null))
        assertEquals(Place.NORMAL, RowActions.placeOf(summary(1, 9), source = null, archiveId = null))
    }

    @Test
    fun swipeArchiveOnlyWhereItMovesMail() {
        assertEquals(RowAction.ARCHIVE, RowActions.swipe(Swipe.ARCHIVE, row()))
        for (place in listOf(Place.ARCHIVE, Place.DRAFTS, Place.TRASH, Place.SPAM)) {
            assertNull(RowActions.swipe(Swipe.ARCHIVE, row(place)), place.name)
        }
    }

    @Test
    fun swipeDeleteGoesToTrashOrDeletesForeverInTheBins() {
        assertEquals(RowAction.DELETE, RowActions.swipe(Swipe.DELETE, row()))
        assertEquals(RowAction.DELETE, RowActions.swipe(Swipe.DELETE, row(Place.ARCHIVE)))
        assertEquals(RowAction.DELETE_FOREVER, RowActions.swipe(Swipe.DELETE, row(Place.TRASH)))
        assertEquals(RowAction.DELETE_FOREVER, RowActions.swipe(Swipe.DELETE, row(Place.SPAM)))
        // Gmail without a visible Trash: Delete is off.
        assertNull(RowActions.swipe(Swipe.DELETE, row(abilities = AccountAbilities(canDelete = false))))
    }

    @Test
    fun swipeTogglesFollowTheRow() {
        assertEquals(RowAction.MARK_READ, RowActions.swipe(Swipe.READ, row(seen = false)))
        assertEquals(RowAction.MARK_UNREAD, RowActions.swipe(Swipe.READ, row(seen = true)))
        assertEquals(RowAction.STAR, RowActions.swipe(Swipe.STAR, row(flagged = false)))
        assertEquals(RowAction.UNSTAR, RowActions.swipe(Swipe.STAR, row(flagged = true)))
        assertNull(RowActions.swipe(Swipe.NONE, row()))
    }

    @Test
    fun onlyArchiveDeleteAndSpamRemoveTheRow() {
        assertEquals(
            setOf(RowAction.ARCHIVE, RowAction.DELETE, RowAction.SPAM, RowAction.NOT_SPAM),
            RowAction.entries.filter { it.removesRow }.toSet(),
        )
    }

    @Test
    fun selectionOfNormalRows() {
        val actions = RowActions.selection(listOf(row(id = 1, seen = false), row(id = 2, flagged = true)))
        assertTrue(actions.archive)
        assertEquals(RowAction.DELETE, actions.delete)
        assertEquals(RowAction.MARK_READ, actions.read)
        assertEquals(RowAction.STAR, actions.star)
        assertEquals(RowAction.SPAM, actions.spam)
        assertEquals(1L, actions.moveAccount)
        assertFalse(actions.labels)
    }

    @Test
    fun selectionTogglesBackWhenEveryRowHasTheState() {
        val actions = RowActions.selection(listOf(row(id = 1, flagged = true), row(id = 2, flagged = true)))
        assertEquals(RowAction.MARK_UNREAD, actions.read)
        assertEquals(RowAction.UNSTAR, actions.star)
    }

    @Test
    fun selectionInTheBins() {
        val trash = RowActions.selection(listOf(row(Place.TRASH, id = 1), row(Place.SPAM, id = 2)))
        assertFalse(trash.archive)
        assertEquals(RowAction.DELETE_FOREVER, trash.delete)
        assertNull(trash.spam) // mixed Trash and Spam

        val spam = RowActions.selection(listOf(row(Place.SPAM, id = 1), row(Place.SPAM, id = 2)))
        assertEquals(RowAction.NOT_SPAM, spam.spam)
        assertEquals(RowAction.DELETE_FOREVER, spam.delete)
    }

    @Test
    fun mixedSelectionHidesWhatDoesNotFitEveryRow() {
        val actions = RowActions.selection(listOf(row(id = 1), row(Place.TRASH, id = 2), row(Place.DRAFTS, id = 3)))
        assertFalse(actions.archive)
        assertNull(actions.delete)
        assertNull(actions.spam)
    }

    @Test
    fun moveAndLabelsNeedOneAccount() {
        val gmail = AccountAbilities()
        val one = RowActions.selection(listOf(row(id = 1, gmail = true, abilities = gmail), row(id = 2, gmail = true)))
        assertEquals(1L, one.moveAccount)
        assertTrue(one.labels)

        val two = RowActions.selection(listOf(row(id = 1, gmail = true), row(id = 2, accountId = 2, gmail = true)))
        assertNull(two.moveAccount)
        assertFalse(two.labels)

        assertFalse(RowActions.selection(listOf(row(Place.TRASH, gmail = true))).labels)
    }

    @Test
    fun gmailWithoutTrashOrSpamTurnsThoseOff() {
        val off = AccountAbilities(canDelete = false, canSpam = false)
        val actions = RowActions.selection(listOf(row(gmail = true, abilities = off)))
        assertNull(actions.delete)
        assertNull(actions.spam)
    }

    @Test
    fun selectionHelpers() {
        assertEquals(setOf(1L, 2L), RowActions.toggle(setOf(1L), 2))
        assertEquals(setOf(1L), RowActions.toggle(setOf(1L, 2L), 2))
        assertEquals(setOf(2L), RowActions.prune(setOf(1L, 2L), listOf(2L, 3L)))
        assertEquals(emptySet(), RowActions.prune(emptySet(), listOf(1L)))
    }

    @Test
    fun pagingGrowsNearTheEndOfAFullPage() {
        val page = RowActions.PAGE
        assertEquals(page, RowActions.nextLimit(page, loaded = page, lastVisible = 10))
        assertEquals(2 * page, RowActions.nextLimit(page, loaded = page, lastVisible = page - 5))
        // Fewer rows than the limit: everything is loaded.
        assertEquals(page, RowActions.nextLimit(page, loaded = 20, lastVisible = 19))
    }

    @Test
    fun labelStatesFromMemberships() {
        val states = RowActions.labelStates(mapOf(1L to setOf(10L, 11L), 2L to setOf(10L), 3L to setOf(10L)))
        assertEquals(mapOf(10L to ToggleableState.On, 11L to ToggleableState.Indeterminate), states)
        assertEquals(emptyMap(), RowActions.labelStates(emptyMap()))
    }

    @Test
    fun labelChangesAddAndRemoveOnlyWhatChanged() {
        val initial = mapOf(10L to ToggleableState.On, 11L to ToggleableState.Indeterminate, 12L to ToggleableState.On)
        val final = mapOf(
            10L to ToggleableState.Off,
            11L to ToggleableState.On,
            12L to ToggleableState.On,
            13L to ToggleableState.On,
            14L to ToggleableState.Off,
        )
        val (add, remove) = RowActions.labelChanges(initial, final)
        assertEquals(setOf(11L, 13L), add)
        assertEquals(setOf(10L), remove)

        val (none, gone) = RowActions.labelChanges(initial, initial)
        assertTrue(none.isEmpty() && gone.isEmpty())
    }

    @Test
    fun mixedLabelIsSetFirstThenToggles() {
        assertEquals(ToggleableState.On, RowActions.nextLabelState(ToggleableState.Indeterminate))
        assertEquals(ToggleableState.Off, RowActions.nextLabelState(ToggleableState.On))
        assertEquals(ToggleableState.On, RowActions.nextLabelState(ToggleableState.Off))
    }
}
