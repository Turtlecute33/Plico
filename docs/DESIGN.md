# Plico — design (v2)

Plico is a privacy-first Android email client for **Riseup** and **Gmail** accounts.
This document is the single source of truth for the implementation.

- Research with sources: `docs/research/` (ids such as `gmail G8`).
- Design review findings: `docs/review/` (ids such as `push PB1`, `protocol PS1`, `privacy PS1`,
  `simplicity EM1`, `apis AF1`). v2 includes every finding marked valid or partly valid.

## 1. Goals and fixed decisions

| Area | Decision |
|---|---|
| Platform | Kotlin, Jetpack Compose, Material 3 (expressive), minSdk 35, targetSdk/compileSdk 37 |
| Release | Sideload + GitHub. No Play, no F-Droid constraints. |
| Push | IMAP IDLE in one `specialUse` foreground service. No FCM, no server. |
| Protocol code | Own Kotlin IMAP + SMTP + MIME code in a pure-JVM module `:mail`. No mail library. |
| Gmail sign-in | App password (always), browser OAuth and Google account on the phone (both need the user's own Google Cloud OAuth client, §6). |
| Riseup sign-in | Account password, SASL PLAIN. |
| E2EE / Tor | None. |
| Local data | Android file-based encryption. Secrets encrypted with an Android Keystore key. No SQLCipher. |
| Offline | Default: headers for all mail, bodies for the last 30 days, older bodies on open. Setup offers "all mail offline". |
| v1 views | Unified inbox, per-account folders/labels, swipe actions. No threads, no two-pane. |

Priorities, in order: fast notifications with low battery use; privacy and security without
usability loss; full Riseup and Gmail feature support; elegant, minimal code.

Non-goals (v1): other providers, conversation view, two-pane, OpenPGP, Tor, snooze, scheduled send,
CardDAV, HTML compose, server-side filter UI (link out instead).

## 2. Dependencies

Only these. Adding one needs a written reason here.

- `kotlinx-coroutines-android` (`:app` only; `:mail` has no dependencies)
- Compose BOM: `ui`, `foundation`, `material3` 1.5.0-beta01 (no tooling, no previews)
- `activity-compose`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`
- `navigation3-runtime`, `navigation3-ui`, `lifecycle-viewmodel-navigation3` (per-screen ViewModels are
  scoped to their back-stack entry and cleared when it leaves). The back stack is a
  `mutableStateListOf<Route>()` in a ViewModel (no serialization plugin). After process death the app
  opens the mailbox.
- `org.jsoup:jsoup` — HTML sanitizing and HTML-to-text. Security-critical; never hand-written.
- `androidx.webkit:webkit` — dead proxy (`ProxyController`) and `setAttributionRegistrationBehavior`.
- `androidx.core` is transitive (through activity); used for `FileProvider` only.
- Data file: `app/src/main/assets/public_suffix_list.dat` (Mozilla PSL, MPL-2.0), refreshed each release.
- Tests: `junit:junit`, `kotlin("test")`, `kotlinx-coroutines-test`.

Platform APIs replace the rest: `android.database.sqlite`, `SharedPreferences`, `AlarmManager` +
foreground service, `HttpsURLConnection` + `org.json`, platform `BiometricPrompt`, `ImageDecoder`,
`AccountManager`, `Notification.Builder`.

`:mail` is packaged as an Android library (namespace `app.plico.mail`, minSdk 35) only so that it
compiles against `android.jar` and its own lint run checks every JDK call against API 35 (NewApi).
The code stays pure Kotlin with no Android imports (`apis AF13`).

## 3. Modules and packages

```
:mail   pure Kotlin (Android-library packaging), no Android imports, blocking I/O, fully unit-tested
  app.plico.mail.net     MailStream (interface), MailSocket, SocketHooks
  app.plico.mail.imap    ImapValue, ImapReader, ImapConnection, Command, ImapClient, UidSet,
                         MailboxName (modified UTF-7), types, exceptions
  app.plico.mail.mime    HeaderBlock, EncodedWords, Addresses, ContentType, Dates,
                         TransferDecoding, MimePart + BodyStructure, MessageBuilder, HeaderSafety
  app.plico.mail.smtp    SmtpClient
  app.plico.mail.sasl    Sasl
:app    Android application
  app.plico              PlicoApp (object graph), MainActivity
  app.plico.data         Db, Schema, daos, models, Vault, Prefs, Files (safe paths)
  app.plico.account      Providers, Credentials, GoogleOAuth, DeviceGoogleAuth, OAuthRedirectActivity
  app.plico.sync         AccountSession, MailboxSync, GmailSync, Ops, Sender, Drafts, Bodies, Search
  app.plico.push         PushService, PushEngine, KeepalivePolicy, Scheduler, AlarmReceiver,
                         NetworkWatcher, Power, StartReceiver
  app.plico.notify       Notifier, ActionReceiver
  app.plico.html         HtmlSanitizer, LinkCleaner, Idn, Psl, SenderCheck, RemoteFetcher
  app.plico.ui           theme, nav, setup, mailbox, message, compose, settings, components
```
One process. The foreground service and the UI share the singletons in `PlicoApp`.

## 4. `:mail` module

Blocking I/O; callers use `Dispatchers.IO`. One thread may block in `read` while another thread
writes `DONE`. Writes are serialized by a lock. Nothing in `:mail` is called from the main thread.

### 4.1 `net`
```kotlin
interface SocketHooks {
    /** After TCP connect, before TLS. App sets TCP_USER_TIMEOUT via ParcelFileDescriptor.fromSocket + Os.setsockoptInt. */
    fun onConnected(socket: java.net.Socket) {}
    /** Explicit hostname check; app passes HttpsURLConnection.getDefaultHostnameVerifier(). */
    fun verifyHostname(host: String, session: javax.net.ssl.SSLSession): Boolean
}
class MailSocket : java.io.Closeable {
    companion object {
        fun open(host: String, port: Int, hooks: SocketHooks, connectTimeoutMs: Int = 20_000,
                 readTimeoutMs: Int = 60_000,
                 factory: javax.net.ssl.SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory): MailSocket
    }
    val input: java.io.InputStream     // buffered
    val output: java.io.OutputStream   // buffered; caller flushes
    var readTimeoutMs: Int             // 0 = infinite (IDLE)
    fun startCompression()             // RFC 4978 raw DEFLATE both ways; every flush() = SYNC_FLUSH
    override fun close()               // idempotent, safe from any thread
}
```
Construction (`apis AF9`): `val s = factory.createSocket() as SSLSocket; s.connect(InetSocketAddress(host, port), connectTimeoutMs)`
(keeps the hostname for SNI and CT); `hooks.onConnected(s)`; enabled protocols `TLSv1.3, TLSv1.2`
only; `sslParameters.endpointIdentificationAlgorithm = "HTTPS"`; `startHandshake()`;
`hooks.verifyHostname` must be true, else close and throw `SSLPeerUnverifiedException`. Implicit TLS
only (IMAP 993, SMTP 465). No STARTTLS, no cleartext.

### 4.2 `imap.ImapValue`, `imap.ImapReader`
```kotlin
sealed interface ImapValue {
    data class Atom(val text: String) : ImapValue        // numbers, flags (\Seen), section keys
    class Str(val bytes: ByteArray) : ImapValue { fun text(): String }   // quoted or literal, UTF-8
    data class ListV(val items: List<ImapValue>) : ImapValue
    data object Nil : ImapValue
}
data class RespCode(val name: String, val args: List<ImapValue>)
sealed interface ImapResponse
data class Tagged(val tag: String, val status: String, val code: RespCode?, val text: String) : ImapResponse
data class Untagged(val number: Long?, val keyword: String, val values: List<ImapValue>,
                    val code: RespCode?, val text: String?) : ImapResponse
data class Continuation(val text: String) : ImapResponse
class ImapReader(input: java.io.InputStream) { fun read(): ImapResponse }
```
- Tokens: atom, quoted string (`\"` `\\`), literal `{n}` / `{n+}` + CRLF + n bytes, list, `NIL`.
  (No `~{n}`: BINARY is not used.)
- An atom with `[` continues through the matching `]` (spaces and parentheses included), then an
  optional `<n>`: `BODY[HEADER.FIELDS (FROM TO)]`, `BODY[1.2]<0>`.
- Status responses (`OK NO BAD BYE PREAUTH`): optional `[CODE args]`, then free text to EOL.
- `* <n> KEYWORD` sets `number`.
- `[` starts bracket mode only after `BODY`, `BODY.PEEK`, `BINARY`, `BINARY.PEEK`, `BINARY.SIZE`.
- Limits: literal ≤ 8 MB, line ≤ 1 MB, all literals of one response ≤ 64 MiB, list nesting ≤ 256 →
  `ImapProtocolException` (attachments are fetched in 1 MB partials, §7.8).

### 4.3 `imap.ImapConnection`, `imap.Command`
```kotlin
class ImapConnection(private val stream: MailStream, private val log: (String) -> Unit = {}) : Closeable {
    var capabilities: Set<String>; private set           // upper-case
    var onUnsolicited: (Untagged) -> Unit                  // EXISTS/EXPUNGE/FETCH seen during any command
    fun greeting(): Untagged
    fun execute(command: Command, onUntagged: (Untagged) -> Unit = {}): Tagged   // NO -> ImapNoException, BAD -> ImapBadException
    fun execute(command: Command, onUntagged: (Untagged) -> Unit, onContinuation: (String) -> String?): Tagged  // SASL challenges
    fun idle(hooks: IdleHooks, onEvent: (IdleEvent) -> Unit)
    fun done()                                             // thread-safe; see rules
    fun startCompression()
    override fun close()
}
interface IdleHooks {
    fun beforeBlockingRead() {}    // app releases the session wake lock
    fun afterRead() {}             // app re-acquires it (60 s) and calls engine.onWake()
    fun stopRequested(): Boolean   // session "work pending" flag
}
sealed interface IdleEvent { data object Changed : IdleEvent; data object Keepalive : IdleEvent }
```
IDLE rules (`push PB1`, `protocol PS21`):
- `idle()` writes `tag IDLE`, flushes, reads until `+`. Any EXISTS/EXPUNGE/FETCH before `+` is
  reported as `Changed`. Right after `+`, if `stopRequested()` is true, send `DONE` at once.
- Then loop: `beforeBlockingRead()`, read, `afterRead()`. Untagged `OK` without code →
  `Keepalive`; EXISTS/EXPUNGE/FETCH/RECENT → `Changed`; `BYE` → `ImapByeException`; the tagged OK →
  return.
- `done()`: if `+` was received and DONE not yet sent, write `DONE` + flush; if `+` is pending, set a
  flag so `idle()` sends it after `+`. Idempotent. Callers run it on `Dispatchers.IO`.
- Commands: tags `A1, A2, …`. `Command` builder: `atom()`, `string()` (quoted if printable ASCII
  without `"`/`\`/CR/LF, else literal), `list()`, `raw()`. Literals use `{n+}` with LITERAL+,
  `{n+}` for n ≤ 4096 with LITERAL-, else a synchronizing literal: flush, wait for `+`; a tagged
  response instead of `+` ends the command (`protocol PS14`).
- The log callback never sees credentials: AUTHENTICATE lines and SASL responses → `<redacted>`.
- Fail closed: an error before the tagged response (or `close()`) marks the connection broken and closes
  it; later calls throw `IOException("Connection closed")`. `done()` never throws and never waits for a
  command write (separate IDLE lock).
- Read timeout 60 s during commands; 0 during IDLE from `+` until the tagged OK (the app's 20 s refresh
  watchdog closes the socket if the OK never comes).
- EXISTS from SELECT/EXAMINE also reaches `onUnsolicited`; unsolicited FETCH responses with a UID during
  `uidFetch` also reach `each`. Callers must be idempotent.

### 4.4 `imap.ImapClient`
```kotlin
class ImapClient(val conn: ImapConnection) : Closeable {
    companion object {
        fun connect(host: String, port: Int, hooks: SocketHooks, log: (String) -> Unit = {}): ImapClient
        val REQUIRED = setOf("IDLE", "MOVE", "UIDPLUS", "ESEARCH")   // checked after login
    }
    val caps: Set<String>
    val isGmail: Boolean                                       // "X-GM-EXT-1" in caps
    fun authenticatePlain(user: String, password: String)      // SASL-IR
    fun authenticateXoauth2(user: String, accessToken: String) // SASL-IR; on '+' challenge send empty line
    fun requireCapabilities()                                  // CAPABILITY if needed; missing -> ImapMissingCapabilityException
    fun enable(vararg ext: String): Set<String>                // ENABLE CONDSTORE (when advertised)
    fun compress()                                             // when COMPRESS=DEFLATE
    fun list(): List<MailboxInfo>                              // LIST "" "*" RETURN (SPECIAL-USE)
    fun status(mailbox: String): MailboxStatus                 // MESSAGES UIDNEXT UIDVALIDITY (+ HIGHESTMODSEQ when CONDSTORE)
    val selected: String?
    fun select(mailbox: String): SelectResult
    fun examine(mailbox: String): SelectResult
    fun uidSearch(criteria: Command): UidSet                   // UID SEARCH RETURN (ALL) [CHARSET UTF-8] <criteria>
    fun uidSearchCount(criteria: Command): Long                // UID SEARCH RETURN (COUNT) [CHARSET UTF-8] <criteria>
    fun uidSearchMax(criteria: Command): Long?                 // UID SEARCH RETURN (MAX) (backfill step)
    fun fetch(seqRange: String, items: String, each: (FetchItem) -> Unit)                 // by sequence (first-sync window)
    fun uidFetch(uids: UidSet, items: String, changedSince: Long? = null, each: (FetchItem) -> Unit)
    fun uidStoreFlags(uids: UidSet, add: Boolean, flags: List<String>)    // .SILENT
    fun uidStoreLabels(uids: UidSet, add: Boolean, labels: List<String>)  // X-GM-LABELS.SILENT
    fun uidMove(uids: UidSet, dest: String): Map<Long, Long>   // COPYUID mapping old -> new
    fun uidExpunge(uids: UidSet)
    fun append(mailbox: String, flags: List<String>, message: ByteArray): Long?   // APPENDUID
    fun create(mailbox: String)                                // ALREADYEXISTS is success
    fun quota(mailbox: String = "INBOX"): Quota?
    fun noop()
    fun idle(hooks: IdleHooks, onEvent: (IdleEvent) -> Unit)
    fun stopIdle()                                             // = conn.done()
    fun logout()
}
data class MailboxInfo(val name: String, val delimiter: String?, val attributes: Set<String>)
data class SelectResult(val exists: Long, val uidValidity: Long, val uidNext: Long,
                        val highestModSeq: Long?, val noModSeq: Boolean)
data class MailboxStatus(val messages: Long, val uidNext: Long, val uidValidity: Long, val highestModSeq: Long?)
data class FetchItem(val seq: Long, val uid: Long, val flags: Set<String>?, val modSeq: Long?,
                     val internalDate: Long?, val size: Long?, val gmMsgId: Long?,
                     val gmLabels: List<String>?, val bodyStructure: ImapValue? /* parseBodyStructure() */, val sections: Map<String, ByteArray>)
data class Quota(val usedKiB: Long, val limitKiB: Long)
```
- Mailbox names are always modified UTF-7 on the wire. Never `ENABLE UTF8=ACCEPT`. Gmail
  `X-GM-LABELS` values are astrings in modified UTF-7; system labels start with `\`.
- `UidSet`: sorted ranges; `toString()` = `1:5,8,10:12`; `*` parses to Long.MAX_VALUE; split into
  commands of at most 500 ranges.
- No COPY fallback for MOVE, no plain SEARCH fallback, no VANISHED (`protocol PS28`, `simplicity EM14`).
- Search with non-ASCII text: `UID SEARCH CHARSET UTF-8 <criteria with literal>` (`protocol PS16`).

### 4.5 `mime.MimePart`, BODYSTRUCTURE
```kotlin
data class MimePart(val section: String, val type: String, val subtype: String,
                    val params: Map<String, String>, val contentId: String?, val encoding: String,
                    val size: Long, val disposition: String?, val dispositionParams: Map<String, String>,
                    val children: List<MimePart>) {
    val mimeType: String; val fileName: String?; val isMultipart: Boolean
}
fun parseBodyStructure(value: ImapValue): MimePart
fun MimePart.displayParts(): DisplayParts
fun MimePart.displayParts(referencedCids: Set<String>): DisplayParts   // unreferenced cid images -> attachments
data class DisplayParts(val html: List<MimePart>, val plain: List<MimePart>,
                        val inline: List<MimePart>, val attachments: List<MimePart>)
```
- Sections: non-multipart top level = `"1"`; children `1..n`; nested `1.2.3`.
- `displayParts` (`protocol PS19`): `multipart/alternative` → the richest supported child
  (html > plain). `multipart/related` → its root (first child) plus cid images as `inline`.
  `multipart/mixed` (and related without an HTML root) → every inline text part in order (inline =
  no `attachment` disposition and no filename). The view joins them; plain parts are HTML-escaped
  when joined with HTML. Images without a cid reference and everything else (including
  `message/rfc822`) are attachments.

### 4.6 `mime` utilities
```kotlin
class HeaderBlock(val fields: List<Pair<String, String>>) { operator fun get(name: String): String?; fun all(name: String): List<String>
    companion object { fun parse(bytes: ByteArray): HeaderBlock } }   // unfold; raw 8-bit: UTF-8 else ISO-8859-1
object EncodedWords { fun decode(value: String): String; fun encode(value: String): String }
data class Address(val name: String?, val email: String) { fun format(): String }
object Addresses { fun parseList(value: String): List<Address>; fun format(list: List<Address>): String; fun isValidAddrSpec(s: String): Boolean /* = AddrSpec.isValid */ }
data class ContentTypeValue(val type: String, val subtype: String, val params: Map<String, String>)
object ContentType { fun parse(value: String): ContentTypeValue }    // RFC 2231 continuations + charset
object Dates { fun parse(value: String): Long?; fun formatUtc(epochMs: Long): String }
object TransferDecoding { fun decode(bytes: ByteArray, encoding: String): ByteArray; fun text(bytes: ByteArray, charset: String?): String
    fun decodingStream(input: java.io.InputStream, encoding: String): java.io.InputStream }
object HeaderSafety { fun clean(value: String): String; fun messageIds(value: String): List<String> }
```
- `TransferDecoding.text`: charset aliases (`utf8`, `ks_c_5601-1987`→EUC-KR, `iso-8859-8-i`→ISO-8859-8,
  unknown → UTF-8, invalid UTF-8 → windows-1252).
- `HeaderSafety.clean` (`privacy PS3`): C0 controls and DEL → space, trims. Every outgoing header
  value passes it. `messageIds` keeps only tokens matching `<[^\s<>]+@[^\s<>]+>`.
- `AddrSpec.isValid`: strict; no CR, LF, `<`, `>`, unquoted space, controls; RFC 5321 length limits.

### 4.7 `smtp.SmtpClient`
```kotlin
class SmtpClient(stream: MailStream, log: (String) -> Unit = {}) : Closeable {
    companion object { fun connect(host: String, port: Int, hooks: SocketHooks, log: (String) -> Unit = {}): SmtpClient } // MailSocket + start()
    fun start()                                          // greeting + EHLO [127.0.0.1]
    val extensions: Map<String, String>; val maxSize: Long?
    fun authPlain(user: String, password: String)
    fun authXoauth2(user: String, accessToken: String)   // on 334 challenge send empty line, then expect 5xx
    fun send(from: String, recipients: List<String>, message: ByteArray)
    fun quit()
}
class SmtpException(val code: Int, val enhanced: String?, message: String, val stage: Stage) : java.io.IOException(message) {
    enum class Stage { CONNECT, EHLO, AUTH, MAIL, RCPT, DATA, FINAL }
    val transient: Boolean get() = code in 400..499     // RCPT rejections use SmtpRecipientException
}
class SmtpRecipientException(val rejected: Map<String, String>) : java.io.IOException()
```
- EHLO argument is always `[127.0.0.1]`. No CHUNKING, DSN or PIPELINING.
- `send`: every address must pass `isValidAddrSpec` before any byte is written. `MAIL FROM:<..> SIZE=n`
  (+ `SMTPUTF8` when needed). Each `RCPT TO`: 5xx, or 4xx with an address error code (4.1.x, e.g. Riseup
  `450 4.1.2` for a mistyped domain) → `RSET`, no DATA, `SmtpRecipientException` (`protocol PS15`); other 4xx →
  `RSET`, transient `SmtpException(RCPT)`; 421 → transient at once. A refused DATA → `RSET`. `DATA`, dot-stuffing, `CRLF.CRLF`. The reply to the
  final dot uses a 10-min read timeout; a timeout there throws `SmtpUncertainException` ("may have been sent").
- Logging redacts `AUTH` lines and 334 exchanges (`privacy PS18`).

## 5. Data (`data`)

Framework SQLite (`SQLiteOpenHelper`, WAL, `PRAGMA foreign_keys=ON`). One `Db` class: `query`,
`tx {}`, `changes: SharedFlow<Set<String>>` (table names) that drives `Flow` queries. Schema v1:

```sql
CREATE TABLE account(
  id INTEGER PRIMARY KEY AUTOINCREMENT, provider TEXT NOT NULL,      -- 'riseup' | 'gmail'
  email TEXT NOT NULL UNIQUE, sender_name TEXT NOT NULL DEFAULT '', label TEXT NOT NULL,   -- label default 'Riseup'/'Gmail' (+ n)
  color INTEGER NOT NULL DEFAULT 0,
  auth TEXT NOT NULL,                       -- 'password' | 'oauth' | 'device'
  secret BLOB, device_account TEXT,
  body_days INTEGER NOT NULL DEFAULT 30,    -- 0 = all mail offline
  push_mode INTEGER NOT NULL DEFAULT 0,     -- 0 instant, 1 instant on Wi-Fi/charging + poll on mobile data, 2 poll
  poll_minutes INTEGER NOT NULL DEFAULT 15,
  gmail_categories TEXT NOT NULL DEFAULT 'primary',
  archive_folder INTEGER,                   -- Riseup: chosen archive folder id (NULL = auto)
  signature TEXT NOT NULL DEFAULT '',
  state INTEGER NOT NULL DEFAULT 0, state_text TEXT,   -- 0 ok, 1 sign-in needed, 2 TLS problem, 3 setup problem (e.g. Gmail All Mail hidden)
  server_keepalive_s INTEGER, quota_used INTEGER, quota_limit INTEGER, sort INTEGER NOT NULL DEFAULT 0);
CREATE TABLE identity(id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  email TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', UNIQUE(account_id, email));
CREATE TABLE folder(
  id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  path TEXT NOT NULL,                       -- decoded mailbox name, or Gmail label (system labels keep the backslash)
  name TEXT NOT NULL, role TEXT,            -- inbox sent drafts trash junk archive all important | NULL
  kind INTEGER NOT NULL DEFAULT 0,          -- 0 IMAP mailbox, 1 Gmail label view
  delimiter TEXT, hidden INTEGER NOT NULL DEFAULT 0,   -- hidden kind-0 = not synced (e.g. Gmail [Gmail]/Drafts); hidden kind-1 = label not in LIST
  notify INTEGER NOT NULL DEFAULT 0, guard INTEGER NOT NULL DEFAULT 0,   -- guard = frozen by the mass-delete guard
  uidvalidity INTEGER NOT NULL DEFAULT 0, uidnext INTEGER NOT NULL DEFAULT 0, modseq INTEGER NOT NULL DEFAULT 0,
  backfill_uid INTEGER NOT NULL DEFAULT 0,  -- headers synced for UIDs >= this; 1 = complete; 0 = never synced
  synced_at INTEGER NOT NULL DEFAULT 0, UNIQUE(account_id, kind, path));
CREATE TABLE message(
  id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  folder_id INTEGER NOT NULL REFERENCES folder(id) ON DELETE CASCADE,  -- source mailbox (kind 0)
  uid INTEGER NOT NULL, gm_msgid INTEGER,
  message_id TEXT, in_reply_to TEXT, refs TEXT,
  subject TEXT NOT NULL DEFAULT '', from_name TEXT NOT NULL DEFAULT '', from_email TEXT NOT NULL DEFAULT '',
  to_list TEXT NOT NULL DEFAULT '', cc_list TEXT NOT NULL DEFAULT '', bcc_list TEXT NOT NULL DEFAULT '', reply_to TEXT NOT NULL DEFAULT '',
  date INTEGER NOT NULL,                    -- INTERNALDATE (sort, body window, notify rule)
  size INTEGER NOT NULL DEFAULT 0,
  seen INTEGER NOT NULL DEFAULT 0, flagged INTEGER NOT NULL DEFAULT 0, answered INTEGER NOT NULL DEFAULT 0,
  draft INTEGER NOT NULL DEFAULT 0, modseq INTEGER NOT NULL DEFAULT 0,
  attachments INTEGER NOT NULL DEFAULT 0, snippet TEXT NOT NULL DEFAULT '',
  auth INTEGER NOT NULL DEFAULT 0,          -- 0 unknown, 1 dmarc=pass, 2 fail (Gmail mx.google.com only)
  unsubscribe TEXT, unsubscribe_post INTEGER NOT NULL DEFAULT 0,
  body INTEGER NOT NULL DEFAULT 0, notified INTEGER NOT NULL DEFAULT 1, hidden INTEGER NOT NULL DEFAULT 0,
  UNIQUE(folder_id, uid));
CREATE INDEX message_account_date ON message(account_id, date DESC);
CREATE INDEX message_gm ON message(account_id, gm_msgid);
CREATE INDEX message_mid ON message(account_id, message_id);
CREATE TABLE message_folder(folder_id INTEGER NOT NULL REFERENCES folder(id) ON DELETE CASCADE,
  message_id INTEGER NOT NULL REFERENCES message(id) ON DELETE CASCADE, PRIMARY KEY(folder_id, message_id)) WITHOUT ROWID;
CREATE INDEX message_folder_message ON message_folder(message_id);
CREATE TABLE body(message_id INTEGER PRIMARY KEY REFERENCES message(id) ON DELETE CASCADE, html TEXT, text TEXT);
CREATE TABLE attachment(id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER NOT NULL REFERENCES message(id) ON DELETE CASCADE,
  section TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, encoding TEXT NOT NULL,
  cid TEXT, inline INTEGER NOT NULL DEFAULT 0);      -- file path is derived: cacheDir/att/<message_id>/<id>
CREATE VIRTUAL TABLE message_fts USING fts4(subject, people, body, tokenize=unicode61);   -- rowid = message.id
CREATE TRIGGER message_ad AFTER DELETE ON message BEGIN DELETE FROM message_fts WHERE rowid = old.id; END;
CREATE TABLE op(id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  kind TEXT NOT NULL, message_ids TEXT NOT NULL DEFAULT '', arg TEXT NOT NULL DEFAULT '{}',
  attempts INTEGER NOT NULL DEFAULT 0, run_at INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, error TEXT);
CREATE TABLE draft(id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  message_id TEXT NOT NULL,                 -- stable for every revision and the final send
  from_email TEXT NOT NULL, to_list TEXT NOT NULL DEFAULT '', cc_list TEXT NOT NULL DEFAULT '', bcc_list TEXT NOT NULL DEFAULT '',
  subject TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', in_reply_to TEXT, refs TEXT,
  source_message INTEGER, attachments TEXT NOT NULL DEFAULT '[]',   -- JSON [{file,name,mime,size}], file = safe name in filesDir/outbox/<id>/
  on_server INTEGER NOT NULL DEFAULT 0,     -- a revision was APPENDed
  state INTEGER NOT NULL DEFAULT 0,         -- 0 editing, 1 queued, 2 sending, 3 failed, 4 sent (follow-up ops pending), 5 failed but may have been sent
  send_at INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, error TEXT, updated INTEGER NOT NULL);
CREATE TABLE contact(email TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '', last_used INTEGER NOT NULL DEFAULT 0, uses INTEGER NOT NULL DEFAULT 0);
CREATE TABLE trusted_sender(email TEXT PRIMARY KEY);
```
Rules:
- Every message has a `message_folder` row for its source mailbox; Gmail label views add rows from
  `X-GM-LABELS`. Every list = `message JOIN message_folder WHERE folder_id IN (…) AND hidden = 0
  ORDER BY date DESC`. "Starred" is virtual (`flagged = 1`). Unified inbox = all `role = 'inbox'`.
- Rows whose `message_id` equals a local `draft.message_id` are hidden from every list (`protocol PS7`).
- Unread counts are computed (`GROUP BY folder_id` over `seen = 0 AND hidden = 0`), never stored (`simplicity EM10`).
- Upserts use `INSERT … ON CONFLICT DO UPDATE` or `INSERT OR IGNORE`; never `REPLACE` (cascade) (`protocol PS10`).
- FTS rows: `INSERT OR REPLACE INTO message_fts(rowid, …)`.
- Account removal: cascade, delete `outbox/` and `att/` dirs of the account, `deleteNotificationChannel`,
  `INSERT INTO message_fts(message_fts) VALUES('optimize')`, `PRAGMA wal_checkpoint(TRUNCATE)` (`privacy PS8`).

`Vault`: AES-256-GCM key `plico.vault.v1` in AndroidKeyStore (StrongBox if available, else TEE),
no user-auth or unlocked-device binding. Ciphertext = 12-byte IV + GCM output.

`Prefs` (no secrets): swipe actions, undo-send seconds, remote-content default, confirm links, dark
email, app lock + timeout, hide in recents, block screenshots, incognito keyboard, hide notification
content, Google OAuth client ID, push paused flag, learned keepalive intervals (≤ 16 keys, LRU),
pending OAuth `{state, verifier, email, createdAt}` (single use, 10 min).

`Files` (`privacy PS1`): one helper for every on-disk name: `<base>/<ownerId>/<itemId>` or
`<base>/<ownerId>/<safeName>`; `safeName` replaces `/ \ :` NUL, C0/C1 and bidi controls
(U+202A–202E, U+2066–2069) with `_`, strips leading dots, max 100 chars, fallback `attachment`;
`canonicalPath` must start with `base.canonicalPath + "/"`. The original name lives only in the DB.

Contacts are learned from sent recipients and from senders the user replied to. No contacts permission.

## 6. Providers and sign-in (`account`)

| | Riseup | Gmail |
|---|---|---|
| IMAP / SMTP | `mail.riseup.net:993` / `:465` | `imap.gmail.com:993` / `smtp.gmail.com:465` |
| Login | full address | full address (OAuth: the id_token email) |
| Auth | PLAIN (SASL-IR) | PLAIN (app password) or XOAUTH2 |
| Max message | 25,600,000 bytes | 35,882,577 bytes (APPENDLIMIT 35,651,584) |
| Sent copy | client APPENDs to `\Sent` | server saves it; never APPEND |
| Notes | IPv4 only; `Spam` name is fixed; Trash 21 d / Spam 7 d auto-delete; settings at account.riseup.net | session ~24 h (password) / token lifetime (OAuth); 15-connection limit; Send-as for non-Google addresses ends Jan 2027 |

Server settings are hardcoded (no autoconfig, MX or ISPDB lookups). No bare-username retry.

```kotlin
interface Credentials {
    suspend fun authImap(client: ImapClient)
    suspend fun authSmtp(client: SmtpClient)
    val expiresAt: Long?          // elapsedRealtime when the IMAP session must be renewed (OAuth/device)
    suspend fun invalidate()
}
```
- `PasswordCredentials`: Riseup password; Gmail app password (spaces removed).
- `OAuthCredentials`: refresh token in Vault, access token in memory. Before every IMAP connect, the
  token must have ≥ 45 min left, else refresh (`push PB17`). `expiresAt` = token expiry − 5 min.
  `invalid_grant` → `AuthRequired`. Account removal: `POST https://oauth2.googleapis.com/revoke`
  with the token in the body.
- `DeviceCredentials`: `getAuthToken(account, "oauth2:https://mail.google.com/", null, false, …)`.
  The acquired time is kept in memory; unknown or older than 45 min → `invalidateAuthToken` + get
  again (at most once per 10 min). `expiresAt` = acquired + 55 min. `KEY_INTENT` → `AuthRequired`.
  `UNREGISTERED_ON_API_CONSOLE` → setup message (client not registered).
- Gmail `* BYE` for an expired session = normal reconnect (no backoff step; invalidate the token).

**Google OAuth client (both OAuth methods).** Google ties an Android OAuth client to the package
name and the signing SHA-1, and each pair can exist in only one Cloud project (`privacy PS9/PS10`).
So both OAuth methods are shown only after the user enters their own client ID in Settings →
Gmail sign-in (the setup screen has the same field and a short guide: create a project, consent
screen "External / In production", a non-personal support email, an Android client for
`app.plico` + the release SHA-1). `local.properties` `plico.googleClientId` presets it in **debug
builds only**. Release builds never contain a client ID. The README tells the user to sign a public
GitHub APK with a different key or applicationId than their personal build; public-APK users have
the app-password method.

Browser OAuth (`GoogleOAuth`): PKCE S256, `state`, scopes `https://mail.google.com/ openid email`,
`login_hint`, `access_type=offline`, `prompt=consent`, redirect `${applicationId}:/oauth2redirect`.
Before `ACTION_VIEW` (default browser; no WebView, no Custom Tabs), store `{state, verifier, email,
createdAt}` in Prefs. `OAuthRedirectActivity` (exported, VIEW + BROWSABLE, the scheme) reads and
deletes the entry, checks `state` and age ≤ 10 min, and starts `MainActivity` (singleTop) with the
code; setup exchanges it. The XOAUTH2 user is the `email` claim of the id_token (direct TLS
response; no signature check needed); the UI shows "Signed in as …". "Sign in again" overwrites the
stored token; it never revokes after the new consent (`privacy PS16`). Google may refuse custom
schemes for new clients (`gmail G9`); the setup text says so and points to the app password.

Device account (`DeviceGoogleAuth`): shown only when a `com.google` authenticator exists and a client
ID is set. Setup launches `AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"),
null, null, null, null)`, stores the name, then calls the Activity overload of `getAuthToken` once
so the consent screen shows during setup (`simplicity EM21`, `apis AF5`).

`AuthRequired` → `account.state = 1`, one "Sign-in needed" alert, no retries until the user acts.

## 7. Sync (`sync`)

### 7.1 `AccountSession`
One per account. It owns at most one `ImapClient` and one worker coroutine (the **loop**) on
`Dispatchers.IO`. All IMAP work of the account runs on the loop, in order.

```kotlin
class AccountSession(...) {
    fun kick(reason: Reason)                              // never writes to a socket on the caller's thread
    suspend fun <T> request(block: suspend LoopScope.() -> T): T   // UI work: queued, run by the loop, awaited
    fun nextDueAt(): Long?                                // earliest of keepalive, OAuth expiry, backoff, poll, op run_at, send_at
    val connectedNetwork: android.net.Network?
    fun close()
}
```
- `kick()` sets `workPending = true`, sends to a conflated channel, and launches `client.stopIdle()`
  on `Dispatchers.IO`. The loop clears the flag before it starts work, checks it again just before
  it sends IDLE, and `IdleHooks.stopRequested()` returns it (`push PB1`).
- Loop: connect → authenticate → `requireCapabilities` → COMPRESS → `ENABLE CONDSTORE` → work →
  IDLE (instant mode) or LOGOUT (poll mode; or after 2 min without work when the account is not
  instant at that moment). "Work" = the steps below in this order:
  1. **Fast path** for the IDLE mailbox (§7.2 step 4 / §7.3 fast path) → commit → **notify** (§9).
  2. Due ops (§7.4) and due sends (§7.5).
  3. Queued `request {}` blocks (UI: open folder, load body, attachment, search).
  4. Flag/expunge sync of the IDLE mailbox, the notify-folder sweep, other changed folders.
  5. Bodies in the window for new rows, then one bounded backfill step (only on unmetered network or
     while the app is visible).
  6. **UIDNEXT check** before IDLE (`push PB2`, `protocol PS3`): SELECT the IDLE mailbox if needed; if
     its UIDNEXT is higher than expected (Riseup: `folder.uidnext`; Gmail: last INBOX UIDNEXT + new
     inbox messages of this cycle), run the fast path again.
- IDLE `Changed` → debounce 1 s under the wake lock (batch bursts) → kick. `Keepalive` → report to
  `KeepalivePolicy`.

### 7.2 Mailbox sync (every kind-0 mailbox; Riseup and Gmail)
1. Folder list: on first connect, then at most every 6 h, and when an unknown label appears. LIST
   with SPECIAL-USE. Roles from attributes (`\Sent \Drafts \Trash \Junk \Archive \All`); INBOX by
   name. Riseup archive (`protocol PS5`, `simplicity EM5`): `account.archive_folder`, else the
   `\Archive` mailbox, else a mailbox named `Archive` or `Archives` (case-insensitive), else CREATE
   `Archive` on the first archive action. Missing Sent/Drafts/Trash: CREATE on first use. Missing
   `Spam`: CREATE exactly `Spam` on the first spam action. Never rename or delete `Spam`. `\Noselect`
   mailboxes are not stored.
2. Cheap check: STATUS (or LIST-STATUS when advertised). Skip the mailbox only when UIDNEXT,
   MESSAGES and a known HIGHESTMODSEQ are all unchanged (`protocol PS10`).
3. SELECT. UIDVALIDITY changed → drop pending ops that use this folder (refetch-undo, §7.4), then
   delete the local rows — through the guard (step 6) when more than 50 rows.
4. **New mail**: skip when server UIDNEXT ≤ `folder.uidnext`. First sync (`backfill_uid = 0`):
   `FETCH <max(1, EXISTS−199)>:* (UID …)`, set `backfill_uid` to the lowest UID fetched (`simplicity EM3`).
   Later: `UID FETCH <folder.uidnext>:*`; ignore returned UIDs below the start. Items: `UID FLAGS
   INTERNALDATE RFC822.SIZE MODSEQ BODY.PEEK[HEADER.FIELDS (DATE FROM TO CC BCC REPLY-TO SUBJECT
   MESSAGE-ID IN-REPLY-TO REFERENCES CONTENT-TYPE LIST-UNSUBSCRIBE LIST-UNSUBSCRIBE-POST
   AUTHENTICATION-RESULTS)]` (+ `X-GM-MSGID X-GM-LABELS` on Gmail). Then `folder.uidnext = UIDNEXT`.
5. **Flags**: `UID FETCH 1:* (UID FLAGS [X-GM-LABELS]) (CHANGEDSINCE <modseq>)`, update existing rows
   only. NOMODSEQ, no CONDSTORE, or HIGHESTMODSEQ went backwards → `UID FETCH <newest 200 local> (UID
   FLAGS)` on push events, the full local range when the folder is opened or every 6 h.
6. **Expunge** (`protocol PS4`): skip when `backfill_uid = 1` and SELECT EXISTS = local row count of
   the mailbox (hidden rows included). Otherwise compare `UID SEARCH RETURN (COUNT) UID <a>:<b>` per
   window of 5,000 local rows; for windows that differ, `RETURN (ALL)` and delete the missing rows.
   **Guard** (`protocol PS9`, `simplicity EM12`): not for `trash`/`junk`; if one pass would delete
   > 50 % of a mailbox's rows and > 50 rows, set `folder.guard = 1` and stop syncing that folder (ops
   on it wait). The folder shows a banner "Many messages are gone on the server · Resync". Resync drops
   the rows, resets uidvalidity/uidnext/modseq/backfill and clears the guard.
7. **Bodies** for rows in the window (`date ≥ now − body_days`, or all when `body_days = 0`):
   `BODYSTRUCTURE`, then the display text parts (`BODY.PEEK[<section>]`, each ≤ 2 MB; larger text
   parts are shown as an attachment), batches of 25. Inline images and attachments on demand.
8. **Backfill** (one step per cycle): `UID FETCH <max(1, backfill_uid−500)>:<backfill_uid−1>` headers
   (sequence numbers are not used, because UIDs are sparse; when the range returns nothing, continue
   with `UID SEARCH RETURN (MAX) UID 1:<backfill_uid−1>`). Set `backfill_uid = 1` when done.
- Sync never changes `seen flagged answered draft hidden` or label rows of a message that a pending op
  references (`protocol PS2`).
- `Changed` events from IDLE run steps 4, 5 (CHANGEDSINCE) and 6 for the IDLE mailbox only.

### 7.3 Gmail
- Mailboxes (kind 0): `\All` (synced), `\Junk`, `\Trash` (synced), `\Drafts` (stored with `hidden = 1`,
  for APPEND and draft deletion), found by SPECIAL-USE, never by name.
- **Missing mailboxes** (`protocol PS13`): no `\All` → `account.state = 3` "Turn on Show in IMAP for All
  Mail (Gmail settings → Labels)", no sync. No `\Trash` or `\Junk` → same hint, Delete/Spam off for
  that account, sync continues. EXISTS of All Mail exactly 1000/2000/5000/10000 → one note about
  Gmail's IMAP folder size limit.
- Label views (kind 1): `\Inbox` (inbox), `\Sent` (sent), `\Draft` and `\Drafts` (drafts),
  `\Important` (important), and every LIST entry that is not `\Noselect`, has no SPECIAL-USE attribute
  and is not named INBOX (`simplicity EM23`). An `X-GM-LABELS` value without a view row creates a
  kind-1 row (`hidden = 1` until LIST shows it) and the membership (`protocol PS6`). A label that LIST
  shows for the first time while a view row has no members: EXAMINE it, `UID FETCH 1:* (X-GM-MSGID)`,
  map msgids to local rows (index `message_gm`), write memberships.
- All Mail uses §7.2 with `X-GM-MSGID X-GM-LABELS`. A row's membership = source mailbox + label views.
- **IDLE on INBOX. Fast path** on `Changed`: DONE → SELECT All Mail → new UIDs (step 4) → category
  check → commit → notify → rest of the work → SELECT INBOX → UIDNEXT check → IDLE.
- Category filter: one search `UID SEARCH UID <new> X-GM-RAW "category:primary OR category:updates"`
  with the enabled categories (skip when all five are enabled).
- **Refresh wakes** also send `STATUS "<All Mail>" (UIDNEXT HIGHESTMODSEQ)`; a change runs the All Mail
  incremental sync (covers skip-inbox labels and changes made on other devices; `push PB16`, `simplicity EM7`).
- Actions (`protocol PS8`): archive = `-X-GM-LABELS (\Inbox)`; move to label X from view V =
  `+X-GM-LABELS (X)` and `-X-GM-LABELS (V)` (V = `\Inbox` for the inbox views); move to Inbox =
  `+X-GM-LABELS (\Inbox)`; star = `±FLAGS (\Flagged)`; important = `±X-GM-LABELS (\Important)`;
  delete = `UID MOVE` to `\Trash`; spam = `UID MOVE` to `\Junk`; not spam / restore = `UID MOVE` from
  Trash/Spam to INBOX; delete forever (in Trash/Spam) = `\Deleted` + `UID EXPUNGE`. Never expunge or
  MOVE with All Mail as the source except to Trash/Junk.
- Never APPEND to Sent.

### 7.4 Pending operations (`Ops`)
User actions change the DB first, then queue an `op`. Kinds: `flags`, `labels`, `move`, `expunge`,
`append` (drafts and the Riseup Sent copy, from a file in `outbox/`), `delete_draft` (by Message-ID).
- `run_at` = now + 6 s for undoable actions (archive, delete, move, spam) so the snackbar can undo
  (undo deletes the op and restores the row). The snackbar closes 1 s before `run_at`; an undo that comes
  after the op started says "Too late to undo". Notification actions and everything else: `run_at = now`.
- The loop runs due ops in id order. `nextDueAt()` includes the earliest `run_at`, so the alarm wakes
  the session (`push PB7`, `protocol PS12`, `simplicity EM1`).
- The runner resolves UIDs from the rows at run time. It SELECTs the folder first; a UIDVALIDITY
  mismatch drops the op (`protocol PS11`).
- After MOVE, apply COPYUID: update `folder_id`/`uid` of the row in place and keep body/attachments
  (`protocol PS26`); without COPYUID delete the row.
- Errors (`protocol PS14`): transient = IOException, `BYE`, `[UNAVAILABLE] [INUSE] [SERVERBUG]
  [THROTTLED]`; backoff 5 s, 30 s, 2 m, 10 m, 1 h; give up after 24 h. Every other NO/BAD is permanent.
  `[TRYCREATE]` on move/append → CREATE the target once and retry. A dropped op is undone by
  **refetching the truth**: `UID FETCH <uids> (FLAGS [X-GM-LABELS])` for its rows (missing → delete the
  row), `hidden = 0`, and a snackbar/banner with the server text.
- Before APPEND, check the size against APPENDLIMIT / the provider limit.

### 7.5 Sending (`Sender`) — the draft table is the only outbox
- Send: `state = 1`, `send_at = now + undo seconds` (Prefs, default 5; 0 = off). Due sends: `state = 2`
  → build MIME (§10) → write `outbox/<id>/message.eml` → SMTP. On the final 250, in one transaction:
  `state = 4`, queue `append` (Riseup Sent copy with Bcc, `\Seen`), `delete_draft` (if `on_server`),
  `flags +\Answered` (source message), update contacts (`protocol PS1`). When no op references the draft
  any more, delete the row and its outbox dir.
- Errors: `SmtpRecipientException` → `state = 3` with the rejected address(es). 5xx → `state = 3` with the
  server text. Transient (connect, 4xx at MAIL/DATA, IOException before the final dot) → backoff, max 6
  attempts or 24 h, then `state = 3`. `SmtpUncertainException` → `state = 5` "May have been sent — check
  Sent before you retry". At start-up every `state = 2` row becomes `state = 3` with that text.
- Drafts view shows failed drafts (states 3 and 5) with Edit and Retry; Retry of a state-5 draft asks first.
  A failed send shows a mailbox banner "N messages not sent · View" and, while the app is not on screen, one
  `alerts` notification "Message not sent" per account (no content). At process start, outbox dirs without
  a draft row are deleted.

### 7.6 Drafts (`Drafts`)
- Every draft has one `message_id` from its first save; all revisions and the final message use it.
- Compose saves the row locally on every change pause (debounced) and on `onStop`. Leaving compose
  queues `append` to the drafts mailbox with `(\Draft \Seen)` (Bcc included) and then `delete_draft`
  for older revisions (`UID SEARCH HEADER Message-ID "<id>"` in the drafts mailbox, all UIDs except the
  new APPENDUID). Riseup deletes with `\Deleted` + `UID EXPUNGE` in Drafts. Gmail: `UID MOVE` to Trash,
  then `\Deleted` + `UID EXPUNGE` of the COPYUID in Trash (`protocol PS7`).
- A draft created by a share intent is local until the user edits it.
- Opening a server draft (a row in the Drafts view) copies it into a local draft with its
  `message_id`, text and attachments (downloaded into `outbox/`).

### 7.7 Search
Local first: FTS4 over subject, people, body. "Search on server": Gmail `X-GM-RAW` in All Mail;
Riseup `UID SEARCH TEXT` in the open folder only (INBOX in the unified view). `CHARSET UTF-8` for
non-ASCII. Hits not yet local are fetched (headers, `notified = 1`); the list shows the local hits plus
the server hits (at most 1,000). The UI shows "Searching on
server…" with Cancel; Cancel closes the socket (normal reconnect) (`simplicity EM15`).

### 7.8 Attachments
`loadAttachment`: `UID FETCH <uid> (BODY.PEEK[<section>]<offset.1048576>)` in a loop into a temp file
(progress = offset / size), then stream-decode (`TransferDecoding.decodingStream`) into
`cacheDir/att/<messageId>/<attachmentId>` (`protocol PS20`). Forward downloads the original
attachments into `outbox/<draftId>/` when the user taps Forward (`protocol PS24`).

## 8. Push and battery (`push`)

### 8.1 Service lifecycle
- `PushService`: `foregroundServiceType="specialUse"` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` = "Keeps a
  user-enabled IMAP IDLE connection to the user's own mail servers for real-time new-mail notifications;
  these servers offer no FCM; dataSync has a 6 h limit and cannot start at boot". `START_STICKY`.
  `onTimeout` → `stopSelf`.
- `onStartCommand` **always** calls `startForeground(ID, n, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` first,
  inside `try/catch (ForegroundServiceStartNotAllowedException)`. On failure: post "Push stopped · Open
  Plico", arm a 1-min exact alarm, return. Only then check "no accounts" / "paused" / exit reason, and
  stop with `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()` (`push PB6`, `apis AF1`).
- Runs while at least one account exists and push is not paused.
- Notification channel `service` (LOW, silent): "Connected" / "Waiting for network" / "Push may be late".
  No addresses. Text updates at most once per 5 s.
- `StartReceiver` (exported=false): `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`,
  `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. Disabled only when the user pauses push in the app.
  It does not start the service when the paused flag is set, except BOOT_COMPLETED, which clears a
  Task-Manager pause. Not `LOCKED_BOOT_COMPLETED`.
- Task Manager stop: at start, if `getHistoricalProcessExitReasons(…).first().reason ==
  REASON_USER_REQUESTED` and the start did not come from the UI or boot → set "paused by you", cancel
  the alarm, post a silent "Push stopped · Resume" notification (its action starts the FGS), stop.

### 8.2 Power grants (Settings → Push & battery, and setup)
1. `POST_NOTIFICATIONS`.
2. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` direct prompt: unrestricted alarms, EXEMPTED bucket, FGS
   restarts from the background.
3. If refused: `SCHEDULE_EXACT_ALARM` (Settings intent).
4. Status rows: each grant; `isBackgroundRestricted` → "Push off: battery usage is Restricted" with a
   button to app settings (`apis AF15`); with neither 2 nor 3: "Push stops if Android closes the app".
   Grants are re-checked at app start and when the screen opens (`push PB23`).

### 8.3 Engine
- `PushEngine`: `SupervisorJob + Dispatchers.IO`. Starts each session's loop. Mode per account from
  `push_mode`: instant, Wi-Fi-instant (instant on unmetered Wi-Fi or while charging, poll on mobile data;
  `push PB14`), poll. Default: instant.
- **Scheduler and alarms** (`push PB3`, `apis AF2`): one `Scheduler` owns one `PendingIntent.getBroadcast(
  …, Intent(ctx, AlarmReceiver::class.java), FLAG_IMMUTABLE)`. `reschedule()` takes the minimum of all
  sessions' `nextDueAt()` (plus a 30-min backstop while there is no network) and arms it with
  `setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` when `canScheduleExactAlarms()` or the battery
  exemption holds, else `setAndAllowWhileIdle`; `SecurityException` → inexact. `AlarmReceiver.onReceive`
  (exported=false): acquire the `alarm` wake lock (10 s; the engine releases it after the sessions got the wake
  and the next alarm is armed; the timeout covers a service start), skip if paused, call
  `engine.onWake(ALARM)` if the engine runs, else `startForegroundService` in try/catch; `reschedule()` in
  `finally`.
- **Coalescing** (`push PB13`): `engine.onWake(source)` runs on every wake: alarm, any server byte
  (`afterRead`), network callback, app visible. It kicks every session whose refresh or poll is due before
  `now + W` (refresh: W = max(3 min, interval / 3); poll: interval / 3). An early refresh never counts
  toward interval growth.
- **Wake locks** (`push PB4`, `apis AF17`): `Power.hold(tag, maxMs = 10 min) { … }` acquires a
  non-refcounted partial wake lock per holder (each session: `sync:<accountId>`; `alarm`; `net`) and
  releases it in `finally`. Inside, `renew()` re-acquires with a 60 s timeout on every server response and
  progress step. `beforeBlockingRead` releases; `afterRead` renews. Tags never contain addresses.
  Target: < 10 min/day.
- **Timers**: never `delay()` for work that must happen with the screen off. Short waits (debounce,
  20 s refresh wait, in-process backoff) run only while the session holds its wake lock. The 20 s
  refresh wait is a `withTimeout` on the waiting coroutine that ends with `socket.close()` (`apis AF9`).
- **`KeepalivePolicy`** (pure Kotlin, unit-tested) per session:
  - Server mode (`push PB12`): cadence `c` = median of the last 5 gaps between consecutive `Keepalive`
    events (`* OK Still here`). Used when `c` < the client interval for the network. Dead-peer due time =
    `lastKeepalive + c + max(30 s, c / 4)`; it fires only if a keepalive is missing. After 2 consecutive
    dead-peer alarms that find the socket alive, use client mode and re-measure. Re-IDLE (DONE + IDLE)
    during a keepalive wake once 25 min have passed since IDLE started.
  - Client mode (`push PB11`): refresh due = `idleStart + interval`. Start 9 min (Gmail), 15 min (others).
    After 3 refreshes in a row that found the socket alive (not early), +2 min (max 28). Failure = a
    refresh that found the socket dead, or a server BYE/close at IDLE age ≥ 5 min without a network change
    (then also cap the interval at age − 1 min). On failure: −2 min (min 5) and **lock**. A locked interval
    never grows; each later failure lowers it again. Unlock on a new network key or after 24 h.
  - Refresh = DONE → tagged OK (20 s) → IDLE → `+`. Timeout → close → reconnect.
  - Network key (`push PB19`): `cellular` for every mobile network; `wifi:` + short SHA-256 of the DNS
    servers; `other`. ≤ 16 keys, LRU. No SSID, no location permission.
  - OAuth/device: `Credentials.expiresAt` is a due time; at that wake, LOGOUT and reconnect with a fresh
    token. If a refresh would happen less than 5 min before `expiresAt`, reconnect instead.
  - `TCP_USER_TIMEOUT` = 30 s on every socket.
- **Network** (`push PB5`): `registerDefaultNetworkCallback`; every callback holds the `net` lock (30 s)
  through a 2 s debounce. New default network → reconnect only sessions on another `Network`. VALIDATED
  gained / unblocked → reconnect only sessions that are not connected. At a wake without network the watcher
  reads the default network again (a missed callback). No default network → close
  sockets, park sessions (no backoff), service text "Waiting for network", 30-min backstop alarm.
- **Backoff** (`push PB10`): healthy = IDLE `+` received and the connection stayed up ≥ 2 min (or one
  refresh succeeded). Only healthy resets the step. Steps: 0 s, 5 s, 20 s (in process, under the lock),
  then alarms 2, 5, 15, 30, 60 min, ±20 % jitter. Throttled (`[UNAVAILABLE]`, "Too many simultaneous
  connections", SMTP 4xx) starts at the 2-min alarm step. A user action makes one immediate attempt
  without changing the step. A network change resets at most once per new `Network`.
- **Error classes** (`push PB9`): `Tls` only for certificate/hostname failures (cause
  `CertificateException`/`CertPathValidatorException`, `SSLPeerUnverifiedException`, hostname check);
  every other `SSLException` is `Network`. `Tls` uses the alarm backoff (15, 30, 60 min) plus a retry on
  network change, shows the state in the app at once, and posts "Secure connection to <label> failed" only
  after 1 h or on a second network. A TLS failure while the network lacks VALIDATED is `Network`. Only
  `AuthRequired` stops retries.
- Riseup cost: Dovecot's default 2-min keepalive is about 720 wakes/day per connection. Accounts on one
  phone share the phase only if Riseup's backend sees the public client IP, otherwise each account has its
  own phase (`push PB18`). The account screen shows the measured interval and the estimated cost and
  offers the Wi-Fi-instant and poll modes.
- Notify-folder sweep (`push PB16`): at client refreshes, at Dovecot re-IDLE wakes (≥ 25 min) and after the
  fast path, `STATUS <folder> (UIDNEXT)` for each Riseup folder with `notify = 1` (Gmail: §7.3 All Mail
  STATUS). A change runs that folder's sync.

## 9. Notifications (`notify`)
- Channels: `service` (LOW), `mail_<accountId>` (DEFAULT; named after the account label; the user turns
  an account's notifications off here — the app skips posting when the channel importance is NONE),
  `alerts` (HIGH).
- **Rule** (`simplicity EM2`, `push PB21`): rows are inserted with `notified = 1`, except rows from the
  step-4 new-UID fetch of a folder whose `uidnext > 0` before this sync (not a first sync, not after a
  UIDVALIDITY reset). Such a row notifies when unseen, `date ≥ now − 7 d`, in a notify scope (Riseup:
  INBOX or `folder.notify = 1`; Gmail: `\Inbox` label or a label view with `notify = 1`) and the Gmail
  category filter passes. Then `notified = 1`.
- Posting order (`push PB8`, `apis AF7`): per account and batch, build the summary from the DB and post it
  **first**, with alert on (`setOnlyAlertOnce(false)`) when the batch has new mail. Then children for the 5
  newest new messages (`BigTextStyle`: sender, subject, snippet when known), `CATEGORY_EMAIL`, group
  `acct_<id>`, `GROUP_ALERT_SUMMARY`. Older new mail shows as "+N more" in the InboxStyle summary. Keep
  fewer than 40 children over all accounts (cancel the oldest). Content-only updates (snippet, cancels) use
  `setOnlyAlertOnce(true)`, batched at most once per second per account. Cancel the summary when no child is left.
- Privacy (`privacy PS11`): `VISIBILITY_PRIVATE` with a public version "N new messages" (+ the label only
  when the user set one); public versions never contain an address. "Hide content" (or app lock on) →
  content-free everywhere. `setAllowSystemGeneratedContextualActions(false)`.
- Actions: Archive, Delete (`setAuthenticationRequired(true)`), Mark read → `ActionReceiver`
  (exported=false; explicit intents; `FLAG_IMMUTABLE`) → `goAsync()` → local change + op (`run_at = now`) +
  kick; if the engine is not running, start `PushService` (allowed from a notification action).
- PendingIntents are unique per message: `Intent.setIdentifier("msg:<id>:<action>")` (`apis AF6`). Taps
  target the non-exported `activity-alias .OpenMessage` with data `plico:message/<id>` (`privacy PS15`);
  `MainActivity` is `singleTop`, handles `onNewIntent`, and reads internal routes only from the alias.
- A message that becomes seen, archived or deleted (local or sync) cancels its child and updates the summary.

## 10. Outgoing messages (`mime.MessageBuilder`)
```kotlin
class MessageBuilder(val from: Address, val to: List<Address>, val cc: List<Address>, val bcc: List<Address>,
                     val subject: String, val text: String, val messageId: String, val inReplyTo: String?,
                     val references: List<String>, val attachments: List<OutgoingAttachment>, val date: Long) {
    fun build(includeBcc: Boolean): ByteArray   // includeBcc = true only for Drafts and the Riseup Sent copy
}
```
- Headers in order: `Date` (UTC `+0000`), `From`, `To`, `Cc`, [`Bcc` only when `includeBcc`], `Subject`
  (RFC 2047 when non-ASCII), `Message-ID` (`<UUIDv4-UPPER@from-domain>`, created once per draft),
  `In-Reply-To`, `References` (first + last 10 ids), `MIME-Version: 1.0`, `Content-Type`,
  `Content-Transfer-Encoding`. All values pass `HeaderSafety.clean`; display names are quoted when they
  contain specials. Structured headers fold at commas/spaces before 78 chars; no line > 998 (`protocol PS23`).
- Never: `User-Agent`, `X-Mailer`, `Disposition-Notification-To`, `Content-Language`, IMAP `ID`. Read
  receipts are never sent or requested.
- Body: `text/plain; charset=utf-8`, `7bit` when ASCII with lines ≤ 998, else quoted-printable. Signature
  after `-- `. Reply attribution: "On <yyyy-MM-dd HH:mm> UTC, <name> wrote:".
- Attachments: `multipart/mixed`, boundary `----` + 30 base36 chars (`SecureRandom`), base64 at 76 chars,
  `Content-Disposition: attachment; filename*=utf-8''…` when non-ASCII, else a quoted filename with `"` and
  `\` escaped.
- Images (`privacy PS17`, `apis AF14`), from every source (pickers and share): JPEG/HEIC/HEIF → JPEG q≈90,
  longest side ≤ 2560 px (ImageDecoder applies orientation); PNG → PNG; static WebP → WebP lossless; GIF and
  animated WebP unchanged with a "metadata kept" note. Names with camera/screenshot date stamps become
  `image-N.<ext>`. "Original quality" per item.

## 11. Reading mail safely (`html`)

### 11.1 Sanitizer (`HtmlSanitizer`, jsoup)
- Custom `Safelist` (from `relaxed`): add `font center span div table thead tbody tfoot tr td th caption
  col colgroup hr`, attributes `style align valign width height bgcolor border cellpadding cellspacing
  color face size dir`, `a href` protocols `http https mailto`, `img src` protocols `cid data http https`.
  Drop `script iframe object embed form input button textarea select link meta base svg math` and `on*`.
- `<style>` blocks: taken out before cleaning, CSS-sanitized (`@import`, `expression`, `behavior`,
  `-moz-binding`, `</` removed), put back into the head.
- Remote resources (`img src/srcset`, `background`, CSS `url()`), when not allowed: removed and counted.
  Tracking pixels always removed and counted: area ≤ 25 px, width or height ≤ 2, `display:none`,
  `visibility:hidden`, `opacity:0`, `max-width/height ≤ 1px`. `cid:` → `https://cid.plico.invalid/<cid>`.
  `http://` image URLs → `https://`.
- Output: CSP `default-src 'none'; img-src data: https://cid.plico.invalid [https: when allowed];
  style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'; upgrade-insecure-requests`,
  `x-dns-prefetch-control: off`, viewport, base CSS (`img{max-width:100%;height:auto}`, word-wrap). **No
  `color-scheme` declaration** (`apis AF3`).
- Plain text for snippets/FTS: jsoup `text()`; `text/plain` without quoted lines.

### 11.2 WebView (`ui.message.MessageWebView`)
- JS off, file and content access off, DOM storage off, geolocation off, mixed content never, cookies off,
  `blockNetworkLoads = true` always, `setAttributionRegistrationBehavior(DISABLED)`, dead proxy
  (`ProxyController` → `0.0.0.0`) set once per process. Manifest: `MetricsOptOut=true`,
  `EnableSafeBrowsing=false`.
- `shouldInterceptRequest`: `cid.plico.invalid` → only `image/*` parts of the message on screen, ≤ 10 MB,
  type from magic bytes, else 403 (`privacy PS13`). `https:` → only when the user allowed remote content,
  through `RemoteFetcher`, with the tracker-host list applied here. Everything else → empty 403.
- `onRenderProcessGone` (`privacy PS7`): destroy the WebView, show the plain-text part ("This message could
  not be shown"), return true; remember the id for the process session and show it as text next time.
- Dark mode: `setAlgorithmicDarkeningAllowed(darkTheme && prefs.darkEmail && !showOriginal)`. The activity
  theme is day/night: `values/themes.xml` parent `android:Theme.Material.Light.NoActionBar`,
  `values-night/themes.xml` parent `android:Theme.Material.NoActionBar`.

`RemoteFetcher` (`privacy PS12`): `HttpsURLConnection`, HTTPS only, no cookies, no Referer, no
Accept-Language, `User-Agent: Mozilla/5.0`, max 3 redirects handled manually. Every hop (and the
unsubscribe POST) rejects IP-literal hosts, `localhost`, single-label names and `.local .lan .home.arpa
.internal` (string check). Per request: 10 s connect/read, 20 s total, 5 MB. Per message: 20 MB, 4 parallel.
The app never declares `ACCESS_LOCAL_NETWORK`.

### 11.3 Links (`LinkCleaner`, `Idn`, `Psl`)
- `shouldOverrideUrlLoading` always returns true. Plain-text bodies use the same link sheet (no autoLink).
- Cleaning: unwrap `google.com/url` (`q`, `url`), `*.safelinks.protection.outlook.com` (`url`),
  `urldefense.com/v3/__…__`, `*.awstrack.me` path, Mandrill `track/click`; strip `utm_*` and `gclid gclsrc
  dclid _ga fbclid igshid msclkid mc_cid mc_eid yclid twclid gbraid wbraid _hsenc __hssc __hstc __hsfp
  hsCtaTracking ml_subscriber vero_id oly_anon_id oly_enc_id icid ef_id s_cid`.
- After unwrapping (`privacy PS5`): parse again; allow only `http`/`https` (`mailto` → composer); reject or
  warn on userinfo, `\`, whitespace and controls; rebuild the URL from the parsed parts; show and open that
  exact string with `Intent(ACTION_VIEW, uri).addCategory(CATEGORY_BROWSABLE)` (no component, no selector).
- Sheet: registrable domain (PSL, `privacy PS6`) in bold, full cleaned URL, punycode for non-ASCII hosts,
  warnings (link text shows another registrable domain; IP host; mixed-script host). Buttons: Open, Copy,
  Open original.
- `Psl`: matcher over `public_suffix_list.dat` (ICANN + PRIVATE, wildcard and exception rules), loaded lazily.

### 11.4 Sender checks (`SenderCheck`)
- Authentication (`privacy PS4`): Gmail only. Parse only the **first** `Authentication-Results` header and use
  it only if its authserv-id is `mx.google.com`: `dmarc=pass` → `auth = 1`; `dmarc=fail`, or `spf=fail` and
  `dkim=fail` → `auth = 2`. Banner only for `auth = 2`. Riseup: no indicator.
- Display name contains an address or a registrable domain different from the sender's; `Reply-To`
  registrable domain differs; first message from this address (local DB, subtle).
- "Always show remote content from this sender" and one-click unsubscribe POST: Gmail only when `auth = 1`
  and the message is not in Junk. Riseup: per-sender trust is From-only; the toggle says "A sender can be
  faked"; no one-click POST (link sheet instead).

### 11.5 Attachments
- Path from `Files` (never the sender's name). Open: `FileProvider` (exported=false,
  `grantUriPermissions=true`, only `<cache-path name="att" path="att/"/>`), read grant only.
- Type (`privacy PS14`): sniff magic bytes; on a mismatch with the declared type, warn and use the sniffed
  type. Warn for effective types `text/html`, `application/xhtml+xml`, `image/svg+xml`, `multipart/related`
  and extensions `.apk .apks .apkm .xapk .exe .msi .bat .cmd .js .jar .scr .vbs .ps1 .lnk .iso .img .html
  .htm .xhtml .xht .svg .mht .mhtml .shtml`, double extensions, U+202E. Never declare `REQUEST_INSTALL_PACKAGES`.
- Save with `ACTION_CREATE_DOCUMENT`. Cache files older than 1 h are deleted at start and when the message closes.

### 11.6 Incoming intents (`privacy PS2`, `simplicity EM6`)
- `IncomingUri.check` for `EXTRA_STREAM` and `ClipData`: only `content://` whose provider package is not Plico
  (`resolveContentProvider(authority, 0)?.packageName != packageName`); reject `file://` and other schemes.
  Typed getters inside try/catch. Copy at once into `outbox/<draftId>/` with a size cap (provider limit).
- `mailto:` (SENDTO, and VIEW + BROWSABLE): parse only `to cc bcc subject body` (RFC 6068); prefilled Cc/Bcc
  rows are shown expanded.
- External intents can only open Compose. The app lock covers every entry.

## 12. Device privacy
- `allowBackup="false"`; `dataExtractionRules` exclude everything in `cloud-backup`, `device-transfer`,
  `cross-platform-transfer`.
- Network security config: `<base-config><certificateTransparency enabled="true"/></base-config>` (CT on
  Android 16+; Android 15 has none; About says so — `apis AF10`). System CAs only (default).
- `setRecentsScreenshotEnabled(false)` (default on); `FLAG_SECURE` option (default off).
- App lock (default off): platform `BiometricPrompt`, `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (no negative
  button), timeout immediately / 1 / 5 / 30 min; UI only; background sync continues.
- Compose and search fields: `InterceptPlatformTextInput` adds `IME_FLAG_NO_PERSONALIZED_LEARNING`
  (default on). Obscured-touch guard on Send, Delete, Show remote content: a `pointerInteropFilter` that
  consumes `ACTION_DOWN` with `FLAG_WINDOW_IS_OBSCURED` (`apis AF8`).
- Credential fields (`privacy PS19`): autofill content types (email, password), password keyboard, no
  autocorrect, cleared after the connection test.
- Logging (`privacy PS18`): R8 strips `Log.v/d/i`; release logs only exception class and reply code, never
  `message`; no `printStackTrace`/`println`. `:mail` log callback is a no-op in release.
- No analytics, crash reporting, downloadable fonts, favicon/BIMI/Gravatar fetches. Hosts contacted: the
  four mail servers, `accounts.google.com`/`oauth2.googleapis.com` (OAuth), remote content after a tap.
- Gmail: a one-time note that Gmail writes the sender's public IP into sent mail.

### Manifest (`apis AF11`)
Permissions: `INTERNET ACCESS_NETWORK_STATE POST_NOTIFICATIONS FOREGROUND_SERVICE
FOREGROUND_SERVICE_SPECIAL_USE RECEIVE_BOOT_COMPLETED WAKE_LOCK REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
SCHEDULE_EXACT_ALARM USE_BIOMETRIC`. Never: `GET_ACCOUNTS USE_EXACT_ALARM ACCESS_LOCAL_NETWORK READ_MEDIA_*
REQUEST_INSTALL_PACKAGES`. Components: `MainActivity` (exported: launcher, SEND, SEND_MULTIPLE, SENDTO
mailto, VIEW mailto BROWSABLE; singleTop), alias `.OpenMessage` (not exported), `OAuthRedirectActivity`
(exported), `PushService`, `StartReceiver`, `AlarmReceiver`, `ActionReceiver` (all not exported),
`FileProvider`.

## 13. UI (`ui`)
Material 3 expressive (`MaterialExpressiveTheme`), dynamic colour, day/night, edge-to-edge, predictive back.
Account colours from a palette of 8. `Route`: `Setup`, `Mailbox(view)`, `Message(id)`, `Compose(draftId, fresh)`,
`Drafts(accountId)`, `Settings`, `AccountSettings(id)`, `About`; `View = Unified | Folder(id) | Starred(accountId?) | Search(query, accountId?)`.

- **Setup**: Welcome (one-line privacy promise; Add Riseup / Add Gmail) → credentials (Riseup: address +
  password; Gmail: App password card always, with a link to `https://myaccount.google.com/apppasswords`;
  "Google account on this phone" and "Sign in with browser" cards when a client ID is set, plus a "Use your
  own Google OAuth client" entry with the guide) → connection test → options (sender name, "Keep all mail
  offline" with a storage note) → permission prompts (§8.2) → mailbox.
- **Mailbox**: search-bar top bar with drawer button. Drawer: All inboxes, Starred, then per account Inbox,
  Sent, Drafts, Archive (Riseup) / All mail (Gmail), Important (Gmail), Spam, Trash, other folders/labels under
  "More"; unread counts on every entry; long-press a folder → "Notify for new mail". Rows: avatar (initial;
  account colour in the unified view), sender (bold when unread), date, subject, 2-line snippet, star, paperclip.
  Swipe (`apis AF4`): per row `remember(msg.id) { SwipeToDismissBoxState(Settled) { it * 0.4f } }`, `onDismiss`
  captures only the id; toggle actions reset the state first, then apply; defaults start→end Archive, end→start
  Delete; choices Archive, Delete, Read/Unread, Star, None; Undo snackbar. Long-press multi-select: Select all,
  Archive, Delete, Read/Unread, Star, Move/Labels, Spam/Not spam. Trash and Spam overflow: Empty (confirm).
  Pull to refresh. Extended FAB "Compose". Banners: sign-in needed, push paused/late, guard, Gmail setup state,
  messages not sent. Rows of sent mail (in Sent, or from the account address) show "To: <first recipient>".
  An account with local drafts and no Drafts mailbox gets a Drafts entry that opens its local drafts.
- **Message**: top bar (back, Archive, Delete, Unread, overflow: Move/Labels with "New…", Spam/Not spam, Star,
  Important (Gmail), Unsubscribe, View headers (`BODY.PEEK[HEADER]` on demand, shows the sender's Date)).
  Subject, Gmail label chips, sender block (name + real address, date, to/cc/bcc expandable), sender-check
  banners, remote-content bar ("Images blocked · N trackers removed" + Show + Always from sender), body
  WebView or plain text, attachment cards with progress, bottom actions Reply / Reply all / Forward. Opening
  marks it seen.
- **Compose**: From (account addresses and identities), To/Cc/Bcc chip fields with local autocomplete,
  Subject, plain-text body, quoted text, attachments (photo picker, document picker, share), size warning
  when (raw size + 4 KB) × 1.37 exceeds the provider limit (the same estimate that Send checks), Send with Undo.
  A reply, forward, mailto or new draft that is left without an edit is discarded. A queued draft opens
  read-only with "Cancel send and edit"; opening it never cancels the send.
  Reply rules (`simplicity EM16`): From = the identity whose address is in the source's To/Cc, else the account
  address; Reply → Reply-To else From (from Sent: its To); Reply all = that + To + Cc minus own addresses; no
  stacked "Re:"/"Fwd:".
- **Settings**: Accounts (add; per account: label, sender name, signature, identities, body days / all offline,
  push mode + poll interval, notifications (channel link) + Gmail categories, measured server keepalive and
  cost, Riseup archive folder, quota (Riseup), Riseup web settings links (filters, vacation, aliases, spam),
  Gmail notes (Send-as Jan 2027, IP in headers), sign in again, remove). Gmail sign-in (OAuth client ID +
  guide). Notifications (hide content). Privacy & security (remote content default, confirm links, dark email,
  app lock + timeout, hide in recents, block screenshots, incognito keyboard). Mail (swipe actions, undo send
  seconds). Push & battery (§8.2). About (version, licence, what the app connects to, CT note).

## 14. Errors
`:mail` throws `IOException` subclasses: `ImapNoException(code, text)`, `ImapBadException`,
`ImapByeException`, `ImapProtocolException`, `ImapMissingCapabilityException`, `SmtpException`,
`SmtpRecipientException`, `SmtpUncertainException`, `SSLException`. The app maps them to `MailError`:
`AuthRequired` (`[AUTHENTICATIONFAILED]`, SMTP 535, `invalid_grant`, consent intent), `Tls` (§8.3),
`Network`, `Throttled`, `Server(text)`, `Setup(text)`.

## 15. Build and tests
- Gradle 9.8.1, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, JDK 21. arm64-v8a. Checks:
  `./gradlew :mail:testDebugUnitTest :app:testDebugUnitTest :mail:lintDebug :app:lintDebug`.
  Release: R8 + resource shrinking, `dependenciesInfo` off, `vcsInfo` off.
- `:mail` tests: tokenizer (literals, sections, resp codes, real Gmail and Dovecot lines), scripted fake server
  (greeting, SASL-IR, literals incl. tagged NO instead of `+`, IDLE: kick before IDLE, kick between IDLE and
  `+`, EXISTS before `+`, keepalive, BYE), BODYSTRUCTURE (Gmail, Dovecot, Apple Mail mixed html, Mailman footer,
  message/rfc822), modified UTF-7, UidSet, encoded words, addresses, RFC 2231, dates, QP/base64 incl. streams,
  MessageBuilder (no banned headers, UTC date, Message-ID, Bcc only with includeBcc, CRLF, folding, CR/LF
  injection in subject/name/filename/References), SMTP (multi-line replies, auth, dot-stuffing, RCPT
  rejection → RSET, 4xx/5xx, address validation).
- `:app` JVM tests: KeepalivePolicy, Scheduler due-time math, LinkCleaner, Idn, Psl, HtmlSanitizer, SenderCheck,
  Files.safeName, notification rule, IncomingUri/mailto parsing, FTS trigger (Robolectric is not used; the DB
  test runs as an instrumented test on the emulator).
- Device checks on the `megaglass-api35` emulator. Live tests with real accounts only after the user agrees,
  with the user typing the credentials.

## 16. Live-test list (needs the user's accounts)
1. Gmail: a label-only change (archive in Gmail web) raises MODSEQ in All Mail (`protocol PS17`). Fallback if
   not: on INBOX `Changed`, compare INBOX EXISTS with the local `\Inbox` count; differ → `UID SEARCH RETURN
   (ALL) X-GM-RAW "in:inbox"` in All Mail and fix memberships.
2. Gmail: unstarring a web-set star with `-FLAGS (\Flagged)` (`protocol PS25`).
3. Gmail: IDLE behaviour (server timeout, keepalives) and the 9-min start value.
4. Riseup: post-auth CAPABILITY, IDLE keepalive interval, `Authentication-Results` authserv-id, no duplicate in
   Sent after APPEND, EHLO in the first Received header.
5. Browser OAuth with a new client ID (custom scheme accepted?) and the device-account token for a registered
   client.
