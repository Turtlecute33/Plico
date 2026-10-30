# Optimizations

Review of Plico on 2026-10-30, at commit b8bb01b on branch main.
Status: review only. No change is applied yet. The owner selects the items to apply.

Rules for this list: the security-review changes (the audit of 2026-10-27: the commits with a 'Report: <ID>' line, run `git log --grep='Report:' --format='%h %s'`; /Users/user/Documents/Github/android/NEXT-STEPS.md has the status of each item in sections 5 and 6 and in the appendix section for this app) stay as they are. No change to storage formats, database schemas, preference keys or encryption. Each applied item is one commit, so that you can revert it alone.

Effort: S = under 1 hour, M = up to half a day, L = more. Status: proposed, approved, applied (<commit>), rejected. applied = done; git log --grep=<ID> shows the commit.

## Summary

Plico is in good shape. Cold start, push and battery, sync, the database and the R8 release build are already lean. The remaining wins are small: one missing TalkBack label, an APK about 9% smaller from a locale filter (estimate), a per-message text cap that stops a crash and push-stop loop, about 28 lines of dead code, four small state and error-handling fixes, and a keyboard Done key that saves in one dialog. Areas in good shape: cold start, push and battery, push service, sync, database, coroutines and errors, release build, dependencies and resources, UI, lifecycle.

## Findings

| ID | Finding | Area | Effort | Risk | Status |
|---|---|---|---|---|---|
| PLICO-OPT-01 | Give the Compose FAB a TalkBack label | ui-ux | S | low | applied |
| PLICO-OPT-02 | Ship only the English resources (localeFilters) | minimalism | S | low | applied |
| PLICO-OPT-03 | Cap the total text that one message downloads | stability | S | low | applied |
| PLICO-OPT-04 | Delete code that nothing references | minimalism | S | low | applied |
| PLICO-OPT-05 | Delete main-code functions that only tests call | minimalism | S | low | applied |
| PLICO-OPT-06 | Keep notification updates alive after one update fails | stability | S | low | applied |
| PLICO-OPT-07 | Refuse to copy a server draft that is too large for a draft row | stability | S | low | applied |
| PLICO-OPT-08 | Keep confirm dialogs and the folder picker open across a configuration change | ui-ux | S | low | applied |
| PLICO-OPT-09 | Show only the remaining Undo time after the Compose screen is recreated | stability | S | low | applied |
| PLICO-OPT-10 | Make the keyboard Done key save in TextFieldDialog | ui-ux | S | low | approved |

### PLICO-OPT-01: Give the Compose FAB a TalkBack label

- **What**: The extended Compose button on the mailbox screen has no accessible name. In material3 1.5.0-beta01, ExtendedFloatingActionButton clears the semantics of its text slot with clearAndSetSemantics {}. Thus the icon label is the only label. Plico sets contentDescription = null. TalkBack reads a button with no name, expanded or collapsed.
- **Where**: app/src/main/kotlin/app/plico/ui/mailbox/MailboxScreen.kt:356 (MailboxScreen, floatingActionButton, Icon)
- **Change**: On line 356, set contentDescription = stringResource(R.string.ui_compose). Keep the text slot as it is. The string exists (strings_ui.xml:33).
- **Benefit**: TalkBack and Switch Access users hear "Compose" on the main action of the main screen. Today this button has no name on each mailbox view.
- **Evidence**: javap on material3-android 1.5.0-beta01 classes.jar (Gradle cache): the text content lambda of ExtendedFloatingActionButton calls SemanticsModifierKt.clearAndSetSemantics at offset 142 with an empty lambda. MailboxScreen.kt:356 has `icon = { Icon(Icons.Edit, contentDescription = null) }`. No other modifier on the FAB sets semantics. git blame 350-362: 7d41d79 (no Report line).
- **Safety**: Changes the semantics of one Icon only. No storage, schema, preference key, key handling or security-review line. No dependency.
- **Verification**: Not tested with TalkBack. After the change, run uiautomator dump on emulator-5554. The FAB content-desc must be "Compose".
- **Effort**: S. **Risk**: low. **Impact**: medium. **Net lines**: about 0 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :mail:test :app:assembleDebug` passed (882 app tests and 461 mail tests, 0 failures). Not checked: no TalkBack or uiautomator check of the FAB label on a device or emulator.
- **Status**: applied

### PLICO-OPT-02: Ship only the English resources (localeFilters)

- **What**: The Plico UI is English only (owner rule "Text: English only"; the app has only res/values and res/values-night). But the release APK has AndroidX and Compose translations of 30 library strings for 85 other locales, for example "Close navigation menu" and "Close sheet". resources.arsc is stored uncompressed. On a phone in another language, these TalkBack labels are not in English, but the rest of the app is.
- **Where**: app/build.gradle.kts:25-95 (android {}, no androidResources block); README.md:116-117 (APK size sentence)
- **Change**: In android {}, add `androidResources { localeFilters += listOf("en") }` with a one-line comment. Copy it from btcpayapp/app/build.gradle.kts:128-136, without the noCompress line. Build the release, measure the APK and update the size in README.md.
- **Benefit**: resources.arsc goes from 483,900 B to about 80-100 KB (estimate). The release APK goes from 4.52 MB to about 4.1-4.15 MB, about 9% smaller (estimate). The installed size decreases by the same quantity, because resources.arsc is stored. All library labels are then in English, as the rest of the UI.
- **Evidence**: Artifact app-release.apk: output-metadata.json versionName 0.2.6 / versionCode 8 = build.gradle.kts:33-34. The build time is 22:47, after HEAD b8bb01b (22:42), so the artifact is current. `unzip -v`: resources.arsc Stored, 483,900 B. `apkanalyzer resources configs --type string`: 86 configurations. `aapt2 dump chunks`: 85 locale string type chunks of 3,768 B each = 320,280 B. Locales use about 80 KB more of the global string pool. Plurals have only the default config. btcpayapp (same AGP 9.4.1) with this filter shows only 'default'. The manifest has no localeConfig and the code has no per-app language API.
- **Safety**: Build packaging setting only. The new block does not edit the packaging excludes from bfde5d1 (PLICO-PERF-008) or the signing code from 6ca3f77 (PLICO-SEC-002). No code, storage, schema, preference key or key handling change. No dependency.
- **Verification**: Measured on the current 0.2.6 artifact with aapt2 and apkanalyzer. DESIGN.md, docs/review and git history have no locale decision. After the change: assembleRelease, apkanalyzer resources configs, and a cold start on emulator-5554. Side effect: TalkBack users with a non-English system language hear these 30 labels in English, as the rest of the app.
- **Effort**: S. **Risk**: low. **Impact**: medium. **Net lines**: about +4 (estimate).
- **Owner check**: MegaGlass-Android commit eb730fa (Report: MEGAGLASS-PERF-006) kept the string locales on purpose, so that library text and TalkBack labels stay in the device language. This item does the opposite. Choose one rule for all apps before you approve it.
- **Owner decision**: English-only library strings in all apps, MegaGlass included (2026-10-30). Apply it.
- **Verified**: `./gradlew :app:testDebugUnitTest :mail:test :app:assembleDebug` passed (882 app tests and 461 mail tests, 0 failures). `./gradlew :app:assembleRelease` passed. In the release APK, `apkanalyzer resources configs --type string` shows only 'default'. resources.arsc (stored) went from 483,900 B to 86,496 B. The APK went from 4,523,110 B to 4,125,710 B (about 8.8% smaller). Not checked: no cold start or TalkBack check on a device or emulator. The README size line is not changed in this commit.
- **Status**: applied

### PLICO-OPT-03: Cap the total text that one message downloads

- **What**: BodyPlan takes up to 50 text parts of up to 2 MB each, with no total limit. runs() lets one heavy message run alone, so the 8 MB run bound of PLICO-PERF-005 (DESIGN section 7.2 step 7; Bodies.kt:103-105) is not true for one message. Only the 64 MiB reader limit stops it. A crafted mail with about 18 inline text parts of 1.9 MB passes every check. Then assemble() holds the raw literals, decoded strings, joined text and joined HTML at the same time. fetchGuarded does not catch an OutOfMemoryError, so BodySkips never skips the row. AccountSession.work catches Throwable, classifies it as MailError.Server and backs off with Kind.NETWORK. Each retry fetches the same batch again before IDLE. On open, MessageViewModel.loadBody catches only Exception, so the process ends.
- **Where**: app/src/main/kotlin/app/plico/sync/Bodies.kt:201 (BodyPlan.texts); app/src/main/kotlin/app/plico/sync/Bodies.kt:64 (fetchGuarded); app/src/main/kotlin/app/plico/sync/AccountSession.kt:439 (work); app/src/main/kotlin/app/plico/account/MailErrors.kt:100 (classify); app/src/main/kotlin/app/plico/ui/message/MessageViewModel.kt:208 (loadBody)
- **Change**: In BodyPlan.texts, after take(MAX_TEXT_PARTS), keep parts only while their sizes add up to at most MAX_FETCH_BYTES (8 MB, an existing private const at Bodies.kt:166). Example: `.let { l -> var sum = 0L; l.takeWhile { sum += it.size; sum <= MAX_FETCH_BYTES } }`. The other parts become attachments through the existing `parts.textParts - texts.toSet()` path. Add one BodiesTest case. Add "and at most 8 MB of text per message" to DESIGN section 7.2 step 7. Line 201 comes from e541b7e (Report: PLICO-SEC-001). Get the owner's approval first.
- **Benefit**: One crafted mail can no longer stop push for an account or crash the app when the user opens it. Today the failure occurs again at each backoff step (up to 60 min), at each refresh and at each push wake. Each try downloads up to about 35 MB again (estimate, Gmail size limit). The documented PERF-005 bound becomes true, also for the open and search paths. Normal mail does not change.
- **Evidence**: Bodies.kt:201 `parts.textParts.filter { it.size <= MAX_TEXT_PART }.take(MAX_TEXT_PARTS)` has no total. Bodies.kt:174 'one heavy item runs alone'. ImapReader.kt:17-20: maxLiteral 8 MiB, maxResponse 64 MiB. Bodies.kt:64-75 catches only ImapProtocolException, ImapNoException and ImapBadException. AccountSession.kt:439 `catch (e: Throwable) { fail(e, c, first) }`. MailErrors.kt:100 `else -> MailError.Server`. AccountSession.kt:841 retries with Backoff.Kind.NETWORK. Backoff.kt:130 steps up to 60 min. MessageViewModel.kt:213 `catch (e: Exception)`. The OOM is an estimate from the code path, not a measurement. It is likely on a 256 MB heap and less certain on a larger heap.
- **Safety**: The change adds a stricter limit on security-review code: Bodies.kt:201 (e541b7e, Report: PLICO-SEC-001). The DESIGN section 7.2 step 7 edit touches DESIGN.md:1055-1059 (e541b7e, Report: PLICO-SEC-001; 7bf4c61, Report: PLICO-PERF-005). It removes or relaxes nothing. No schema, storage format, preference or key change: the extra parts become attachment rows, as parts over 2 MB do now, and only for new body fetches. No dependency.
- **Verification**: NEXT-STEPS sections 5 and 6, DESIGN section 7.2 and docs/review do not decide or reject a per-message total. Verify with :app:testDebugUnitTest (new BodiesTest case) and `ANDROID_SERIAL=emulator-5554 connectedDebugAndroidTest --tests app.plico.sync.BodiesFetchTest`.
- **Effort**: S. **Risk**: low. **Impact**: medium. **Net lines**: about +4 (estimate).
- **Owner check**: This item edits the BodyPlan limit from PLICO-SEC-001 and the documented PLICO-PERF-005 bound. Confirm that a stricter per-message total of 8 MB of text is acceptable, and that the extra text parts can show as attachments.
- **Owner decision**: The owner approved the stricter limit of 8 MB of text per message on the PLICO-SEC-001 line and the DESIGN section 7.2 edit (2026-10-30).
- **Verified**: `./gradlew :app:testDebugUnitTest :mail:test :app:assembleDebug` passed (883 app tests with the new BodiesTest case textAfterEightMegabytesIsAnAttachment, and 461 mail tests, 0 failures). Not checked: no `connectedDebugAndroidTest` run of BodiesFetchTest (no emulator or device was used), no run-time check with a crafted mail, no OOM measurement.
- **Status**: applied

### PLICO-OPT-04: Delete code that nothing references

- **What**: Four items have no reference in main, test, androidTest or :mail: Icons.Link, Icons.Undo, the 2-argument overload connections(accounts, prefs), and the catalog entry kotlinx-coroutines-core. Simplicity review EM14 removed coroutines from :mail, but the catalog line stayed.
- **Where**: app/src/main/kotlin/app/plico/ui/components/Icons.kt:50 (Icons.Link); app/src/main/kotlin/app/plico/ui/components/Icons.kt:53 (Icons.Undo); app/src/main/kotlin/app/plico/ui/settings/SettingsModel.kt:320-327 (connections(accounts: List<Account>, prefs: Prefs)); gradle/libs.versions.toml:17 (kotlinx-coroutines-core)
- **Change**: Delete the two icon lines, the overload with its KDoc and blank line, and the catalog line.
- **Benefit**: About 11 fewer lines to read and keep. The catalog lists only what the build uses. The APK does not change, because R8 removes these items now.
- **Evidence**: rg finds no use of Icons.Link or Icons.Undo in app or mail, and no `with(Icons)` or `Icons::`. All connections( callers pass 4 arguments (AboutScreen.kt:68, SettingsModelTest.kt:271-330). R8 usage.txt lists Icons.getLink(), getUndo(), Link$delegate, Undo$delegate and SettingsModelKt.connections(java.util.List,app.plico.data.Prefs) as removed. Only libs.versions.toml names coroutines-core; mail/build.gradle.kts has no runtime dependency (only two testImplementation lines). git blame: a554352, 4fbdc67, 7d41d79 (no Report line).
- **Safety**: No security-review line. No storage, schema, preference key or key handling. coroutines-core stays a transitive dependency of coroutines-android, so verification-metadata.xml does not change.
- **Verification**: Related to PLICO-OPT-05 (dead code), but a separate commit, because it needs no test edits. Verify with testDebugUnitTest, lintDebug and assembleRelease.
- **Effort**: S. **Risk**: low. **Impact**: low. **Net lines**: about -11 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :mail:test :app:assembleDebug` passed (883 app tests and 461 mail tests, 0 failures). `./gradlew :app:assembleRelease` and `./gradlew :app:lintDebug` passed. The release APK changed, against the estimate: R8 kept the path strings of Icons.Link and Icons.Undo in classes.dex before. classes.dex went from 3,926,776 B to 3,925,924 B and the APK from 4,125,774 B to 4,124,922 B (852 B smaller). Not checked: no run-time check of the UI on a device or emulator.
- **Status**: applied

### PLICO-OPT-05: Delete main-code functions that only tests call

- **What**: Five public production functions have callers only in tests: Psl.publicSuffix, ImapClient.noop, OpDao.nextRunAt, Address.format() and HeaderBlock.all(name). The app uses Ops.nextRunAt, which also skips ops on guarded folders. Push review PB11 rejected NOOP. AuthResults.kt:11 reads only the first Authentication-Results header on purpose. The owner removed a function of this type before, in b9dc969 (PLICO-UX-014).
- **Where**: app/src/main/kotlin/app/plico/html/Psl.kt:41-45 (publicSuffix); mail/src/main/kotlin/app/plico/mail/imap/ImapClient.kt:354-356 (noop); app/src/main/kotlin/app/plico/data/OpDao.kt:28-29 (nextRunAt); mail/src/main/kotlin/app/plico/mail/mime/Addresses.kt:4-7 (Address.format); mail/src/main/kotlin/app/plico/mail/mime/HeaderBlock.kt:6 (all); docs/DESIGN.md:270, 372, 375
- **Change**: Delete the five functions and update the three DESIGN API lines. Point the tests at the production paths. ImapClientTest (4 calls) and SyncEnvTest:50 use client.conn.execute(Command.raw("NOOP")), as SyncEnvTest:54 does. DraftOpDaoTest:116, 126 and 129 drop the nextRunAt asserts (OpsTest covers Ops.nextRunAt). AddressesTest:227-248 uses Addresses.format(listOf(a)). HeaderBlockTest:48 and 56 drop the all() asserts. PslTest uses registrableDomain().
- **Benefit**: About 17 fewer lines of production API in :app and :mail. The tests then check the functions that the app calls, not a second path. The APK does not change, because R8 removes the five functions now.
- **Evidence**: rg finds callers only in PslTest (10), ImapClientTest (4), SyncEnvTest (1), DraftOpDaoTest (3), AddressesTest (about 13) and HeaderBlockTest (2). R8 usage.txt lists OpDao.nextRunAt(long), Psl.publicSuffix(String), ImapClient.noop(), Address.format() and HeaderBlock.all(String) as removed. git blame: ^a554352 for all five and their test lines, except SyncEnvTest.kt:50 (7d41d79) (no Report line).
- **Safety**: No production path calls these functions, so behaviour does not change. Psl matching (registrableDomain, matchedRule, domainsIn), header parsing and the op queue stay as they are. No security-review line. No storage, schema, preference key or key handling.
- **Verification**: Most test edits are rewrites, not deletions. Verify with :mail:testDebugUnitTest, :app:testDebugUnitTest, and connectedDebugAndroidTest on emulator-5554 for DraftOpDaoTest.
- **Effort**: S. **Risk**: low. **Impact**: low. **Net lines**: about -17 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :mail:test :app:assembleDebug` passed (883 app tests and 461 mail tests, 0 failures). `./gradlew :app:compileDebugAndroidTestKotlin` passed. Not checked: connectedDebugAndroidTest (DraftOpDaoTest) did not run, because no emulator was used; no release build, so the APK size effect is not measured; no run-time check of the UI on a device or emulator.
- **Status**: applied

### PLICO-OPT-06: Keep notification updates alive after one update fails

- **What**: Notifier.schedule keeps one coroutine per account in `jobs`. It returns early while the account is in the map. The coroutine removes its entry only when nothing is dirty. When update() throws (a database read or a NotificationManager binder call), the scope handler only logs, and the finished Job stays in `jobs`. After that, refresh(), database changes and the 'Hide content' re-render do nothing for that account until the process restarts. New-mail alerts (postNew) still work, but read, archived or deleted mail stays in the shade. With the push service, the process can live for days.
- **Where**: app/src/main/kotlin/app/plico/notify/Notifier.kt:244 (schedule); app/src/main/kotlin/app/plico/notify/Notifier.kt:263 (update call)
- **Change**: Put `update(accountId, again)` in try/catch(RuntimeException) and log only the class name, as PushEngine.kt:86-90 does. update() is not a suspend function, so the catch cannot swallow a CancellationException.
- **Benefit**: A transient failure delays only one update. The notifications of the account follow the database again at the next change. They do not stay frozen for the rest of the process life.
- **Evidence**: Notifier.kt:246 `if (accountId in jobs) return`. Notifier.kt:257-262: the only `jobs.remove(accountId)` is on the not-dirty path; grep finds no other removal. Notifier.kt:49-53: the scope handler only logs. Notifier.kt:263 calls update() with no try. git blame: 7d41d79, 0b967d7 (no Report line).
- **Safety**: Error handling in notification batching only. hideContent, public versions and other privacy rules do not change. No storage, schema, preference or key change. No dependency.
- **Verification**: No real update() failure was seen. The trigger is rare, but the effect lasts for the full process life. Verify with connectedDebugAndroidTest (NotifierTest) on emulator-5554.
- **Effort**: S. **Risk**: low. **Impact**: low. **Net lines**: about +4 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :mail:test :app:assembleDebug` passed (883 app tests and 461 mail tests, 0 failures). Not checked: connectedDebugAndroidTest (NotifierTest) did not run, because no emulator or device was used; no run-time check with a failed update; no unit test, because Notifier needs the Android app object.
- **Status**: applied

### PLICO-OPT-07: Refuse to copy a server draft that is too large for a draft row

- **What**: editServerDraft copies the full stored text of a server draft into draft.body with no length check. DraftDao reads the body in one piece (COLS) in get, byMessageId, observe, observeAll and observeByAccount. A row over the 2 MB CursorWindow throws SQLiteBlobTooBigException. Commit 1a7c0a8 fixed this crash only for the body table, with chunked reads. MailboxViewModel.localDrafts collects observeAll through stateIn(viewModelScope) with no handler. After one such copy, the mailbox screen crashes at each start until the user clears app data.
- **Where**: app/src/main/kotlin/app/plico/sync/MailActions.kt:443 (editServerDraft, body at :461); app/src/main/kotlin/app/plico/data/DraftDao.kt:24 (observeAll); app/src/main/kotlin/app/plico/ui/mailbox/MailboxViewModel.kt:247 (localDrafts)
- **Change**: In editServerDraft, read bodyText(detail) one time. Throw ActionError with a new string ('This draft is too large to edit in Plico') when it has more than 500,000 characters (at most 1.5 MB of UTF-8). The callers (MailboxViewModel.open, MessageViewModel.editDraft) show an ActionError now.
- **Benefit**: A large server draft gives an error message, not a crash loop that only a data clear can stop. The server draft stays unchanged, and the message view still shows it, because body reads are chunked.
- **Evidence**: MailActions.kt:461 `body = bodyText(detail)`; MailActions.kt:746-747: bodyText returns the full stored text. DraftDao.kt:158-159: COLS includes body; DraftDao.kt:24-26: observeAll selects it for every draft. Db.kt:76-87: observe has no catch. MailboxViewModel.kt:410-411 stateIn(viewModelScope). Commit 1a7c0a8 names the same SQLiteBlobTooBigException for the body table.
- **Safety**: The check goes in editServerDraft (7d41d79, no Report line). bodyText (7dca5da, PLICO-PERF-004) and the reply cap (329e11b, PLICO-PERF-007) do not change. The draft table, its columns and stored data do not change. One new string in values/. No preference or key change, no dependency.
- **Verification**: The trigger is rare: a draft from another client with more than about 2 MB of visible text. This closes only the server-draft path. Typing or repeated paste in Compose has no length limit. Verify with a unit test on editServerDraft and :app:testDebugUnitTest.
- **Effort**: S. **Risk**: low. **Impact**: low. **Net lines**: about +5 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :mail:test :app:assembleDebug` passed (883 app tests and 461 mail tests, 0 failures). `./gradlew :app:assembleRelease` passed; apkanalyzer shows the new string ops_error_draft_too_large in the release APK. Not checked: no unit test, because editServerDraft needs the SQLite store and only the instrumented tests (OpFixture) can make one; no instrumented test ran, because no emulator or device was used; no run-time check with a large server draft. The mailbox and the message view map an ActionError to their generic error text ('That did not work. Try again.', 'The message could not be loaded.'), so the new string does not show at this time.
- **Status**: applied

### PLICO-OPT-08: Keep confirm dialogs and the folder picker open across a configuration change

- **What**: MailboxScreen and MessageScreen keep dialog state in remember. MainActivity has no configChanges and no orientation lock. A rotation, theme change, font-size change or window resize closes 'Delete forever?', 'Empty Trash/Spam?', 'Send again?', 'Unsubscribe?' and the message Move/Labels picker. The selection is in the ViewModel and stays, so the user sees the selection but no dialog. ComposeScreen (119-120) and the settings screens use rememberSaveable for this type of state.
- **Where**: app/src/main/kotlin/app/plico/ui/message/MessageScreen.kt:125-127 (MessageScreen: confirmDelete, confirmUnsubscribe, picker); app/src/main/kotlin/app/plico/ui/mailbox/MailboxScreen.kt:195-197 (MailboxScreen: confirmDelete, confirmEmpty, confirmRetry)
- **Change**: MessageScreen: change remember to rememberSaveable for the two Booleans and for FolderPickerMode? (an enum). MailboxScreen: confirmDelete as a saveable LongArray? (toLongArray()/toList()); confirmEmpty as a saveable Boolean that uses the view folder; confirmRetry as a saveable draft id (Long?) that is found in drafts. Leave PickerRequest in remember. Use only Bundle-safe types, so that the save cannot throw.
- **Benefit**: A destructive confirmation or the folder picker stays open after a rotation or a theme or font change, as in Compose and Settings. The user does not tap Delete forever, Empty or Send again a second time.
- **Evidence**: AndroidManifest.xml:49-53 has no configChanges; grep finds no screenOrientation or requestedOrientation. MailboxScreen.kt:195-198 and MessageScreen.kt:125-127 use remember { mutableStateOf(...) }. The selection is vm.selected (MailboxScreen.kt:126), so it survives. git blame: 7d41d79 (no Report line).
- **Safety**: In-memory saved-instance state (Bundle) of message, folder and draft ids only. This is not a storage format, schema or preference key. No security-review line. No dependency.
- **Verification**: Same root cause as PLICO-OPT-09 (activity recreation), different code. Verify on emulator-5554: open each dialog, rotate, and make sure that it stays open and acts on the same items.
- **Effort**: S. **Risk**: low. **Impact**: low. **Net lines**: about +3 (estimate).
- **Owner check**: Limit found after the change, from the code and the navigation3 1.2.0 bytecode, not on a device. The Navigator is not saved, so after process death the app starts again on the unified inbox. But MainActivity saves the 'screens' state holder, and it keeps the state of each old nav entry under its route key (defaultContentKey). That state comes back only when the user opens the same route again. Then a dialog that was open at process death opens again with no tap: 'Empty Trash?' when the user opens Trash from the drawer, or 'Delete forever?', 'Unsubscribe?' or the Move/Labels picker on the same message. 'Delete N forever?' comes back with the old ids, but the selection is empty, so the user cannot see which messages Confirm deletes. Before this item, these flags were in remember and did not come back. ComposeScreen (confirmDiscard, confirmSend) and AccountSettingsScreen (removing) have this problem already. This item does not fix it. Proposed follow-up item: in MainActivity, when savedInstanceState is not null and the Navigator is new (its back stack is empty at onCreate), call screens.removeState("screens") before the first AppNav composition. Another option is to save the Navigator back stack across process death. Approve a follow-up item, or accept this limit.
- **Verified**: `./gradlew :app:testDebugUnitTest :mail:test :app:assembleDebug` passed (883 app tests and 461 mail tests, 0 failures). Not checked: no run-time check on a device or emulator (open each dialog, rotate, and make sure that it stays open and acts on the same items); no process-death check (see Owner check); no unit test, because the change is Compose state in two screens. The folder picker of the mailbox selection stays in remember, as the item says.
- **Status**: applied

### PLICO-OPT-09: Show only the remaining Undo time after the Compose screen is recreated

- **What**: In Phase.QUEUED, ComposeContent runs LaunchedEffect(Unit) and shows 'Sending… Undo' for the full UndoWindow.sendMs(vm.undoSeconds). The phase is in the ViewModel, which survives recreation. A configuration change during the window runs the effect again with the full time, but send_at was set at the tap. An Undo tap in the extra time gets 'too late' and the screen closes. This breaks the UndoWindow rule 'an Undo the user can tap always still works'. The mailbox uses UndoWindow.actionLeft(since) already.
- **Where**: app/src/main/kotlin/app/plico/ui/compose/ComposeScreen.kt:134-138 (ComposeContent); app/src/main/kotlin/app/plico/ui/compose/ComposeViewModel.kt:331 (send)
- **Change**: Add `var queuedAt = 0L` to ComposeViewModel. Set it to SystemClock.elapsedRealtime() where phase becomes QUEUED (line 331). On ComposeScreen.kt:138, pass `UndoWindow.sendMs(vm.undoSeconds) - (SystemClock.elapsedRealtime() - vm.queuedAt)`. When the rest is not positive, showUndo returns false and the screen closes, as now. Do not edit Undo.kt (21c178a, PLICO-UX-008) or the haptic lines 133-136 (17c77d2, PLICO-UX-011).
- **Benefit**: After a configuration change during the send delay (default 5 s, longer with the accessibility timeout), Undo shows only while it can still work. No 'too late' toast after a visible Undo. No mail is lost today, so the gain is a correct documented rule.
- **Evidence**: ComposeScreen.kt:134-138 `LaunchedEffect(Unit) { ... snackbar.showUndo(sending, undo, UndoWindow.sendMs(vm.undoSeconds)) }`. ComposeViewModel.kt:85: phase is mutableStateOf, set to QUEUED at :331. AppNav.kt:66: rememberViewModelStoreNavEntryDecorator keeps the ViewModel. MailActions.kt:544 calculates the send time at the tap (store.now() + sendDelayMs, defined at :836). MailboxScreen.kt:214 uses UndoWindow.actionLeft(since). git blame: line 138 is 01d4312 (no Report line).
- **Safety**: Changes line 138 (01d4312) and adds one ViewModel field. Undo.kt and the haptic lines from PLICO-UX-011 stay unchanged. No change to storage, schema, preferences, keys or the send delay. No dependency.
- **Verification**: The confirm haptic still fires again on recreation. That line is from PLICO-UX-011 and stays. Verify on emulator-5554: set Undo send to 10 s, send, rotate after about 5 s. The snackbar shows for about 5 s more and Undo works.
- **Effort**: S. **Risk**: low. **Impact**: low. **Net lines**: about +2 (estimate).
- **Verified**: `./gradlew :app:testDebugUnitTest :mail:test :app:assembleDebug` passed (883 app tests and 461 mail tests, 0 failures). Not checked: no run-time check on a device or emulator (set Undo send to 10 s, send, rotate after about 5 s, and make sure that the snackbar shows for about 5 s more and Undo works); no unit test, because the change is in a Compose effect and ComposeViewModel needs PlicoApp. queuedAt is set when the queued result comes back, a little after send_at is calculated; the 1 s margin of UndoWindow covers this, as before.
- **Status**: applied

### PLICO-OPT-10: Make the keyboard Done key save in TextFieldDialog

- **What**: TextFieldDialog (account label, sender name, Google client ID, Microsoft client ID) has a single-line field with no keyboard action. Done and hardware Enter only hide the keyboard and do not save. The client ID field on the setup page (SetupPages.kt:431-433) and New folder (FolderPicker.kt:229-230) save on Done.
- **Where**: app/src/main/kotlin/app/plico/ui/settings/SettingsList.kt:280-342 (TextFieldDialog: field 307-322, Save onClick 328-338)
- **Change**: Move the Save onClick body into a local fun save(). When singleLine, pass keyboardOptions.copy(imeAction = ImeAction.Done) and keyboardActions = KeyboardActions(onDone = { save() }). The Save button calls save(). Leave the multi-line signature dialog and IdentityDialog as they are.
- **Benefit**: Done and hardware Enter save the value after the same check() as the Save button, as in setup and New folder. One shared component fixes four dialogs.
- **Evidence**: SettingsList.kt:307-322 sets no imeAction and no keyboardActions. The callers SettingsScreen.kt:117,134 and AccountSettingsScreen.kt:198,208 pass KeyboardOptions without imeAction. SetupPages.kt:431-433 uses ImeAction.Done with vm.saveClientId(). git blame 280-342: 7d41d79, 01d4312 (no Report line).
- **Safety**: Keyboard handling in one dialog component. The saved values, their preference keys and the check() validation do not change. No dependency.
- **Verification**: Users open these dialogs rarely, so the impact stays low. Verify on emulator-5554: edit the account label and push Done. The value saves and the dialog closes. An invalid value shows the same error as Save.
- **Effort**: S. **Risk**: low. **Impact**: low. **Net lines**: about +4 (estimate).
- **Status**: approved

## Areas in good shape

- Cold start: Application.onCreate does only a Prefs read (a planned decision) and starts one cleanup thread. The DB, PSL, network and crypto work runs off the main thread.
- Push and battery: one coalesced exact alarm, non-refcounted wake locks with timeouts, debounced network callbacks, and receivers with goAsync and a 9 s cap. docs/review/push-battery.md covers the rest.
- Push service: startForeground runs first, the code catches ForegroundServiceStartNotAllowedException, handles onTimeout, and uses stopSelf(startId).
- Sync: CONDSTORE CHANGEDSINCE, one COUNT for expunges, body runs saved one at a time, and a STATUS-only sweep at foreground.
- Database: indexed LIMIT queries, body reads in 256 K chunks, migrations that only add, and UI reads on Dispatchers.IO.
- Coroutines and errors: SupervisorJob scopes, CancellationException is always rethrown, every network call has a timeout, and no user action can start two times.
- Release build: R8 full mode with shrinkResources. The 0.2.6 APK (same as HEAD) has 16 entries, no lib/ or kotlin/, and is 4,523,110 bytes.
- Dependencies and resources: each runtime library is used and has a reason in DESIGN section 2. :mail has no runtime dependencies. The code references all 725 strings and both drawables.
- UI: one Undo model that follows the accessibility timeout, confirmations only on irreversible actions, full empty, loading and error states, consistent Back, and thorough TalkBack work (except the FAB).
- Lifecycle: collectAsStateWithLifecycle, ViewModels scoped to nav entries, and the Compose draft saved at ON_STOP and in onCleared.
