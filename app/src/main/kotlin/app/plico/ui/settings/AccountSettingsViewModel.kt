package app.plico.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.plico.PlicoApp
import app.plico.data.Account
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State and changes of one account's settings. Writes run one at a time off the main thread, each on
 * the newest row, so that quick changes do not overwrite each other.
 */
internal class AccountSettingsViewModel(private val app: PlicoApp, private val accountId: Long) : ViewModel() {
    private val writes = Dispatchers.IO.limitedParallelism(1)

    val account: StateFlow<Account?> = app.accounts.observe(accountId).stateIn(viewModelScope, WHILE_SHOWN, null)

    val identities: StateFlow<List<Identity>> =
        app.accounts.observeIdentities(accountId).stateIn(viewModelScope, WHILE_SHOWN, emptyList())

    /** Riseup mailboxes that can be the archive (DESIGN §7.2 step 1): not the special ones. */
    val archiveFolders: StateFlow<List<Folder>> = app.folders.observeVisible(accountId)
        .map { folders -> folders.filter { it.kind == FolderKind.MAILBOX && it.role !in NOT_ARCHIVE } }
        .stateIn(viewModelScope, WHILE_SHOWN, emptyList())

    /** True while [remove] runs; the screen then ignores input and Back. */
    var removing by mutableStateOf(false)
        private set

    /** Applies [change] to the stored account; a real change reconfigures its session. */
    fun update(change: (Account) -> Account) {
        if (removing) return
        viewModelScope.launch(writes) {
            val current = app.accounts.get(accountId) ?: return@launch
            val next = change(current)
            if (next != current) {
                app.accounts.update(next)
                app.mail.accountChanged(accountId)
            }
        }
    }

    fun addIdentity(email: String, name: String) {
        viewModelScope.launch(writes) {
            app.accounts.addIdentity(accountId, email.trim(), cleanLine(name, Choices.NAME_MAX))
        }
    }

    fun removeIdentity(id: Long) {
        viewModelScope.launch(writes) { app.accounts.removeIdentity(id) }
    }

    /**
     * Removes the account with all its data (DESIGN §5, privacy PS8). The removal finishes even when the
     * screen closes. [onDone] gets true when other accounts are left.
     */
    fun remove(onDone: (othersLeft: Boolean) -> Unit) {
        if (removing) return
        removing = true
        viewModelScope.launch {
            val othersLeft = try {
                withContext(NonCancellable + Dispatchers.IO) {
                    app.mail.removeAccount(accountId)
                    app.accounts.all().isNotEmpty()
                }
            } finally {
                removing = false
            }
            onDone(othersLeft)
        }
    }

    private companion object {
        val WHILE_SHOWN = SharingStarted.WhileSubscribed(5_000)
        val NOT_ARCHIVE = setOf(FolderRole.INBOX, FolderRole.SENT, FolderRole.DRAFTS, FolderRole.TRASH, FolderRole.JUNK)
    }
}
