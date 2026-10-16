package app.plico.ui.compose

import app.plico.data.Account
import app.plico.data.DraftState
import app.plico.data.Identity
import app.plico.data.Provider

/** An address the user can send from: an account address or one of its identities (DESIGN §13). */
internal data class SenderOption(
    val accountId: Long,
    val provider: Provider,
    val accountLabel: String,
    val email: String,
    val name: String,
) {
    /** `Name <email>`, or the address alone. */
    val display: String get() = if (name.isBlank()) email else "$name <$email>"
}

/** The pure rules of the compose screen. */
internal object DraftRules {
    /** What leaving the screen does with the draft (DESIGN §7.6). */
    enum class Leave {
        /** Nothing changed: the draft stays as it is (a shared draft stays local until edited). */
        KEEP,

        /** Save it and queue the server copy ([app.plico.sync.MailActions.leaveDraft]). */
        SAVE,

        /** Nothing in it, or a [fresh] draft the user did not touch (a Reply tapped by mistake): delete it. */
        DISCARD,
    }

    /** [fresh]: the draft was made for this screen ([app.plico.ui.nav.Route.Compose]). */
    fun leave(edited: Boolean, empty: Boolean, fresh: Boolean): Leave = when {
        empty -> Leave.DISCARD
        edited -> Leave.SAVE
        fresh -> Leave.DISCARD
        else -> Leave.KEEP
    }

    /**
     * How Compose opens a draft in [state] (DraftState): editing and failed ones for editing, a queued one
     * read-only (opening must not stop its send), the others are gone (being sent, sent).
     */
    fun openPhase(state: Int): ComposeViewModel.Phase = when (state) {
        DraftState.EDITING, DraftState.FAILED, DraftState.UNCERTAIN -> ComposeViewModel.Phase.EDITING
        DraftState.QUEUED -> ComposeViewModel.Phase.WAITING
        else -> ComposeViewModel.Phase.GONE
    }

    /** Bytes of [s] in UTF-8, without a copy. */
    fun utf8Length(s: CharSequence): Long {
        var bytes = 0L
        var i = 0
        while (i < s.length) {
            val c = s[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }

    /** True when [body] has no text before its signature separator (a line `-- `). */
    fun isBlankBody(body: String): Boolean = body.lineSequence().takeWhile { it != "-- " }.all(String::isBlank)

    /** Every account address, then its identities, in account order. */
    fun senderOptions(accounts: List<Account>, identities: (Long) -> List<Identity>): List<SenderOption> =
        accounts.flatMap { account ->
            val own = SenderOption(account.id, account.provider, account.label, account.email, account.senderName)
            val aliases = identities(account.id)
                .filterNot { it.email.equals(account.email, ignoreCase = true) }
                .map { own.copy(email = it.email, name = it.name.ifBlank { account.senderName }) }
            listOf(own) + aliases
        }

    /**
     * The option for a draft of [accountId] sent from [fromEmail]. An address that is no longer an identity
     * is kept, so that the draft does not change its sender without the user. Null when the account is gone.
     */
    fun pickSender(options: List<SenderOption>, accountId: Long, fromEmail: String): SenderOption? {
        val account = options.filter { it.accountId == accountId }
        return account.firstOrNull { it.email.equals(fromEmail, ignoreCase = true) }
            ?: account.firstOrNull()?.let { if (fromEmail.isBlank()) it else it.copy(email = fromEmail, name = "") }
    }
}
