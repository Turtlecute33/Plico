# full-review
Lens: the full app after 0.2.12, in two sweeps. Sweep 1 went by code area (sync, ops and send, push and notify, data, HTML, account, `:mail`, the UI groups, build hardening, product). Sweep 2 went by flow (lifecycle, battery in 24 h, privacy data flow, smoothness, accessibility, concurrency, mail compatibility). A second reviewer tried to refute each finding: 52 stayed, 13 were refuted. The account and the build-hardening areas had no finding. No critical finding.

Status: `fixed` (in 0.2.13), `open` (not changed, with the reason), `declined` (the owner said no). JVM unit tests, lint, the debug build and the release build pass. No device test was made: the behaviour on a device is not verified.

## Sync and ops

## FR1 [major] [fixed] §7.4 Ops.waits
PROBLEM: A flag op that waits in backoff was overtaken by a later op on the same message. The server ended with the older value, and the next flag sync changed the local row back.
FIX: A flags or labels op waits while an earlier op of the same kind for the same message is queued (`Ops.blockedByEarlier`, `OpOrderTest`).

## FR2 [minor] [fixed] §7.3 GmailSync.fill
PROBLEM: A new label view whose first fill failed was never filled again, because its row was there at the next LIST.
FIX: On a failure, the row of that view and of each later new view is deleted, then the error goes on.

## FR3 [minor] [fixed] §7.2 SyncAccount
PROBLEM: A tagged NO or BAD from one mailbox ended the cycle, so the other mailboxes, the bodies and IDLE did not run, at each reconnect.
FIX: `skipRefused` skips that mailbox until the next sweep. A throttling NO and all other errors still end the cycle. A mailbox that always fails is now silent.

## FR4 [minor] [fixed] §7.5 Sender
PROBLEM: Each send wrote the built message to `outbox/<id>/message.eml`. No main code read it. It was a second plaintext copy, and a full disk made the send fail as a network error.
FIX: The file is not written. `Files.outboxMessage` is removed.

## FR5 [minor] [fixed] §7.5 Sender
PROBLEM: An OutOfMemoryError in the MIME build left the draft in SENDING, and the next start showed "may have been sent".
FIX: The build catches it and fails the draft with the "too large" text.

## FR6 [minor] [open] §7.5 Sender
PROBLEM: A server where Plico saves the Sent copy gets two full MIME builds.
REASON: Bcc goes between Cc and Subject, and the formatter is private in `:mail`. A single build needs a `MessageBuilder` change.

## FR7 [minor] [fixed] §7.7 MailActions.searchServerIds
PROBLEM: In a search of all accounts, one account that failed dropped the hits of the others.
FIX: The hits of the accounts that answered are shown. The error shows only when all accounts fail.

## FR9 [minor] [open] §7.2 MailboxSync.expunge
PROBLEM: A search hit below `backfill_uid` makes each expunge check load all local UIDs.
REASON: A bound at `backfill_uid` would leave a deleted search hit for ever on metered data, where backfill does not run. A stale row is worse than a slow check.

## Data, start and push

## FR8 [minor] [fixed] §5 MessageDao.listSql
PROBLEM: The All inboxes query used `IN (SELECT …)`. SQLite sorted the whole inbox before LIMIT (checked with the sqlite3 CLI on 6,000 rows: TEMP B-TREE FOR ORDER BY).
FIX: A correlated EXISTS, as for a folder. The plan walks `message_date`. A plan assertion is in `MessageDaoTest` (instrumented, not run in this review).

## FR10 [minor] [fixed] PlicoApp start-cleanup
PROBLEM: One exception in the start-cleanup thread ended the process at each start and skipped the later tasks.
FIX: Each task has its own guard. Only the exception class is logged.

## FR11 [minor] [fixed] §8.2 PushEngine
PROBLEM: `refreshStatus`, `pauseByUser` and `resume` made binder calls and armed alarms on the main thread.
FIX: They run in the engine scope (Dispatchers.IO).

## FR27 [major] [fixed] §9 Notifier
PROBLEM: The 1 s gap between notification updates held only for an update that drew something. With one notification shown, each commit of a sync ran a full update.
FIX: Each update sets the time, also a no-op or a failed one.

## FR29 [minor] [fixed] §5 account removal
PROBLEM: The IMAP host of a removed account stayed in the learned keepalive intervals.
FIX: `LearnedIntervals.forget(scope)` at removal, when no other account uses the scope.

## FR30 [minor] [fixed] §6 AccountCredentials.rotate
PROBLEM: A cancel between the token reply and the write lost a rotated refresh token.
FIX: The write is not cancellable.

## Reading and writing mail

## FR12 [major] [fixed] §11.3 LinkCleaner
PROBLEM: A link of about 1.5 MB reached the browser Intent. The Binder limit made the app crash on a tap.
FIX: A URL over 8,192 characters is copy-only, with a shortened text.

## FR13 [minor] [fixed] §11 Idn.toAscii
PROBLEM: The punycode step ran on a host of any length.
FIX: A host over 253 characters is refused first.

## FR14 [minor] [fixed] §4 MailSocket.open
PROBLEM: The connect tried only the first address of the host.
FIX: Each address gets a try, with a share of the timeout (at least 3 s). SNI and the certificate check still use the host name. Not tested with more than one address.

## FR15 [minor] [fixed] §4 SmtpClient
PROBLEM: A non-ASCII domain went raw into MAIL and RCPT on a server without SMTPUTF8.
FIX: The domain goes as punycode there.

## FR40 [major] [fixed] §4.5 format=flowed
PROBLEM: A `format=flowed` plain text showed each soft line break as a real one.
FIX: `unflow` (RFC 3676) in `:mail`, used by `BodyPlan.assemble` (`FlowedTest`).

## FR41 [minor] [fixed] §11 HtmlSanitizer.text
PROBLEM: Lines of invisible padding characters stayed in the text of an HTML mail, in reply quotes and in snippets.
FIX: U+00AD, U+034F, U+200B, U+2060 and U+FEFF do not make a line visible and are not in the snippet. U+200C and U+200D stay.

## FR42 [minor] [fixed] §4.5 displayParts
PROBLEM: An HTML alternative over 2 MB became an attachment, and its plain twin was dropped already: an empty body.
FIX: The alternative choice skips an option over the limit while a smaller one has text.

## FR44 [minor] [fixed] §11.1 Safelist (owner decision)
PROBLEM: The sanitizer dropped `class`, so the class rules of the kept `<style>` blocks did not match: hidden preheaders and dark-mode image twins showed.
FIX: `class` is allowed. `id` is not. The base CSS has no class rule. Check some real newsletters on a device.

## UI

## FR16 [minor] [open] MailboxScreen Undo
PROBLEM: An Undo snackbar is lost when the user opens another folder from the drawer in its 4 s.
REASON: The shared Undo notice cannot carry the plural text without a larger change.

## FR17 [minor] [open] MailboxViewModel Open event
PROBLEM: An Open event can wait in the channel and open Compose later, with no tap.
REASON: The activity is made again on rotation, so a cancel on dispose would stop a load that the user waits for.

## FR18 [minor] [fixed] FolderPicker
PROBLEM: A second tap while the sheet closed moved or applied twice.
FIX: A `closing` flag.

## FR19 [minor] [fixed] DraftsScreen
PROBLEM: A storage error in "Try again" of a failed draft crashed the app.
FIX: The error is caught; the draft stays failed.

## FR20 [minor] [fixed] MailboxScreen
PROBLEM: "Your inbox is empty" could flash before "Loading mail".
FIX: The empty text waits for the accounts and the folder.

## FR21 [minor] [fixed] MailboxFolders.sections
PROBLEM: Each unread change sorted the drawer folders with a new path string for each comparison.
FIX: The path is made one time for each folder.

## FR22 [minor] [fixed] §13 offline banner
PROBLEM: With no network the list looked current and queued changes gave no hint.
FIX: An "Offline" banner, only while push runs (a stopped watcher does not report that the network is back).

## FR23 [minor] [fixed] ComposeViewModel
PROBLEM: An attach failure showed only a general text.
FIX: The text of an `ActionError` shows (too large, type not allowed). No other exception text shows.

## FR24 [minor] [fixed] MessageScreen events
PROBLEM: Open or Save of a large attachment ended in the background, where the start of an activity can fail.
FIX: Events are collected only while the screen is started; they wait in the channel.

## FR25 [minor] [fixed] §6 SetupViewModel.back
PROBLEM: Back during the browser sign-in left the pending entry. A later redirect signed in the cancelled account.
FIX: Back removes the pending entry.

## FR26 [minor] [fixed] §13 Search this sender
PROBLEM: There was no way from a message to the mail of its sender.
FIX: An overflow entry opens the mailbox search for the address.

## FR28 [minor] [fixed] Donate
PROBLEM: The donation card could show after a tap on a notification.
FIX: The notification intent has an extra; the card does not show for it. The intent matching is not changed.

## FR31 [minor] [fixed] MessageViewModel
PROBLEM: The flows of an open message ran while the app was stopped, at each commit of a sync.
FIX: `WhileSubscribed(5_000)`.

## FR32 [minor] [fixed] MessageViewModel.cidImage
PROBLEM: An inline image request held a WebView thread with no time limit.
FIX: A 30 s limit.

## FR33 [minor] [fixed] MailboxViewModel.folders
PROBLEM: Each sync step changed a folder row and made the list context and the drawer again.
FIX: The sync counters are cleared in this flow (the UI reads only "backfill done or not").

## FR34 [minor] [fixed] MailboxScreen picker
PROBLEM: The Move-to and Labels sheet closed on rotation or app lock.
FIX: The view model holds the request.

## FR35 [minor] [fixed] SetupParts.Step
FIX: The step number column grows with the font.

## FR36 [minor] [fixed] NewFolderDialog
FIX: The Create button keeps its name for TalkBack while it shows the indicator.

## FR37 [minor] [fixed] Compose FAB
FIX: TalkBack reads "Compose" one time.

## FR38 [minor] [fixed] ServerSearchRow
FIX: Progress, result and error of the server search are a polite live region.

## FR39 [minor] [fixed] strings
FIX: The failed-draft button says "Try again", as the other screens.

## FR43 [minor] [fixed] README
PROBLEM: The README said that remote content loads only after a tap. Two settings load images without one.
FIX: The README names them.

## FR45 [minor] [fixed] §13 Star toggle (owner decision)
PROBLEM: An open message did not show its star state.
FIX: A star toggle in the top bar in place of the menu entry.

## Open, for the owner

## FR46 [minor] [open] Starred view index
PROBLEM: The Starred query has no index on `flagged`. FIX would be a partial index and schema version 7. Small gain.

## FR47 [minor] [open] fresh draft after a process kill
PROBLEM: A new, empty draft stays in Drafts when the process is killed in Compose. A clean-up at start could delete the draft of a Compose screen that opens at the same time.

## FR48 [major] [open] first WebView on the main thread
PROBLEM: The first HTML message of a process loads the WebView provider in composition. A warm-up needs a measurement on a device first.

## FR49 [minor] [open] PushService start
PROBLEM: `onServiceStarted` makes some binder calls on the main thread. To move them adds a start/stop race.

## FR50 [minor] [declined] baseline profile for app code
## FR51 [minor] [declined] note about the Google grant when a device account is removed
## FR52 [minor] [declined] features from the panel: show as plain text, Lock now, trusted-senders list, blocked tracker hosts, tap the avatar to select, has-attachment filter
