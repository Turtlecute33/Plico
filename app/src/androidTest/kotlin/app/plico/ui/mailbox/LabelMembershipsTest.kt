package app.plico.ui.mailbox

import androidx.compose.ui.state.ToggleableState
import app.plico.data.DbFixture
import app.plico.data.FolderRole
import app.plico.data.Provider
import org.junit.Test
import kotlin.test.assertEquals

class LabelMembershipsTest : DbFixture() {
    private val acc = account("user@gmail.com", Provider.GMAIL)
    private val allMail = mailbox(acc, "[Gmail]/All Mail", FolderRole.ALL)
    private val inbox = label(acc, "\\Inbox", FolderRole.INBOX)
    private val work = label(acc, "Work")
    private val bills = label(acc, "Bills")

    @Test
    fun labelViewsOnlyWithoutTheSourceMailbox() {
        val (a, b, c) = insert(
            header(acc, allMail, 1, labels = setOf(inbox, work)),
            header(acc, allMail, 2, labels = setOf(work)),
            header(acc, allMail, 3, labels = emptySet()),
        )
        val memberships = db.labelMemberships(listOf(a, b, c, 999))
        assertEquals(setOf(inbox, work), memberships[a])
        assertEquals(setOf(work), memberships[b])
        assertEquals(emptySet(), memberships[c])
        assertEquals(emptySet(), memberships[999])

        assertEquals(
            mapOf(work to ToggleableState.On, inbox to ToggleableState.Indeterminate),
            RowActions.labelStates(db.labelMemberships(listOf(a, b))),
        )
        assertEquals(emptyMap(), RowActions.labelStates(db.labelMemberships(emptyList())))
    }

    @Test
    fun manyMessagesAreReadInBatches() {
        val ids = insert(*Array(1_200) { header(acc, allMail, it + 1L, labels = setOf(bills)) })
        val memberships = db.labelMemberships(ids)
        assertEquals(1_200, memberships.size)
        assertEquals(setOf(bills), memberships.values.toSet().single())
    }
}
