package app.plico.ui.mailbox

import app.plico.data.Account
import app.plico.data.AccountState
import app.plico.data.AuthKind
import app.plico.data.Folder
import app.plico.data.FolderKind
import app.plico.data.FolderRole
import app.plico.data.MessageSummary
import app.plico.data.Provider
import app.plico.data.PushMode
import app.plico.push.Grants
import app.plico.push.PushStatus

fun account(
    id: Long,
    provider: Provider = Provider.RISEUP,
    archiveFolder: Long? = null,
    state: AccountState = AccountState.OK,
    stateText: String? = null,
) = Account(
    id = id, provider = provider, email = "user$id@example.org", senderName = "", label = "Account $id",
    color = id.toInt(), auth = AuthKind.PASSWORD, deviceAccount = null, bodyDays = 30, pushMode = PushMode.INSTANT,
    pollMinutes = 15, gmailCategories = setOf("primary"), archiveFolder = archiveFolder, signature = "",
    state = state, stateText = stateText, serverKeepaliveS = null, quotaUsed = null, quotaLimit = null,
    sort = id.toInt(),
)

fun folder(
    id: Long,
    accountId: Long,
    path: String,
    role: FolderRole? = null,
    kind: Int = FolderKind.MAILBOX,
    hidden: Boolean = false,
    notify: Boolean = false,
    guard: Boolean = false,
    delimiter: String? = "/",
    backfillUid: Long = 1,
) = Folder(
    id = id, accountId = accountId, path = path, name = path.substringAfterLast('/'), role = role, kind = kind,
    delimiter = delimiter, hidden = hidden, notify = notify, guard = guard, uidValidity = 1, uidNext = 1, modSeq = 0,
    backfillUid = backfillUid, syncedAt = 0,
)

fun summary(
    id: Long,
    folderId: Long,
    accountId: Long = 1,
    seen: Boolean = true,
    flagged: Boolean = false,
    draft: Boolean = false,
) = MessageSummary(
    id = id, accountId = accountId, folderId = folderId, subject = "s", fromName = "n", fromEmail = "e@example.org",
    date = 0, seen = seen, flagged = flagged, draft = draft, attachments = false, snippet = "",
)

fun pushStatus(
    paused: Boolean = false,
    stopped: Boolean = false,
    notifications: Boolean = true,
    battery: Boolean = true,
    exact: Boolean = true,
    restricted: Boolean = false,
) = PushStatus(
    running = true, pausedByUser = paused, stoppedBySystem = stopped, hasNetwork = true,
    grants = Grants(notifications, battery, exact, restricted),
)
