# Plico — design (v2)

Plico is a privacy-first Android email client for IMAP and SMTP accounts.
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
| Accounts | Any IMAP/SMTP server with TLS (implicit TLS or required STARTTLS). Setup asks only for the address and finds the servers (§6.3). The rules of Gmail, Riseup and Microsoft accounts are internal: the user never selects a provider. |
| Sign-in | Password (SASL PLAIN or LOGIN, only over TLS), app password, browser OAuth for Google and Microsoft with the user's own client ID (§6.5). Gmail also: the Google account on the phone. |
| E2EE / Tor | None. |
| Local data | Android file-based encryption. Secrets encrypted with an Android Keystore key. No SQLCipher. |
| Offline | Default: headers for all mail, bodies for the last 30 days, older bodies on open. Setup offers "all mail offline". |
| v1 views | Unified inbox, per-account folders/labels, swipe actions. No threads, no two-pane. |

Priorities, in order: fast notifications with low battery use; privacy and security without
usability loss; full support of standard IMAP, with every feature of Gmail accounts and of Riseup accounts kept;
elegant, minimal code.

Non-goals (v1): conversation view, two-pane, OpenPGP, Tor, snooze, scheduled send, CardDAV, HTML compose,
server-side filter UI (link out instead). Also:
- POP3, Exchange EWS/ActiveSync, Microsoft Graph (also as a send fallback), JMAP.
- Proton (its Bridge is desktop-only), Tuta and HEY (no IMAP): setup refuses them with a clear text.
- Self-signed or private-CA certificates, certificate pinning per account, client certificates.
- Plaintext IMAP/SMTP, "STARTTLS if available", autoconfig over HTTP, Thunderbird ISPDB or any other
  third-party lookup.
- OAuth for other providers (Yahoo, AOL, Fastmail, Zoho …): they use app passwords.
- CRAM-MD5, SCRAM, NTLM, GSSAPI. Password sign-in is PLAIN or LOGIN over TLS only.
- Shared and other users' namespaces: their mailboxes are stored hidden and not synced (§7.2).
- IMAP `ID`, except the minimal ID that NetEase servers need (§7.1).
- An OAuth tenant per account (Microsoft uses `common`), Apple "authorize with Apple Account".

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
`AccountManager`, `Notification.Builder`, `DnsResolver` (raw SRV and MX queries at setup, §6.3) and
`javax.xml.parsers` (DOM, for the autoconfig file, §6.3). The support for every IMAP server added no dependency.

`:mail` is packaged as an Android library (namespace `app.plico.mail`, minSdk 35) only so that it
compiles against `android.jar` and its own lint run checks every JDK call against API 35 (NewApi).
The code stays pure Kotlin with no Android imports (`apis AF13`).

## 3. Modules and packages

```
:mail   pure Kotlin (Android-library packaging), no Android imports, blocking I/O, fully unit-tested
  app.plico.mail.net     MailStream (interface), MailSocket, SocketHooks, Endpoint, TlsMode,
                         StartTlsException, TlsHandshakeException
  app.plico.mail.imap    ImapValue, ImapReader, ImapConnection, Command, ImapClient, UidSet,
                         MailboxName (modified UTF-7), types (incl. Namespaces), exceptions
  app.plico.mail.mime    HeaderBlock, EncodedWords, Addresses, ContentType, Dates,
                         TransferDecoding, MimePart + BodyStructure, MessageBuilder, HeaderSafety
  app.plico.mail.smtp    SmtpClient
  app.plico.mail.sasl    Sasl
:app    Android application
  app.plico              PlicoApp (object graph), MainActivity
  app.plico.data         Db, Schema, daos, models, Vault, Prefs, Files (safe paths)
  app.plico.account      Providers (traits), ProviderTable, Credentials (Password, OAuth, Device),
                         BrowserOAuth + OAuthIssuer, DeviceGoogleAuth, AccountProbe, MailErrors,
                         OAuthRedirectActivity
  app.plico.account.discovery  Discovery, Autoconfig, DnsWire, SystemDns
  app.plico.sync         AccountSession, MailboxSync, GmailSync, FolderRules, Ops, Sender, Drafts, Bodies,
                         Search
  app.plico.push         PushService, PushEngine, KeepalivePolicy, PushModes, Scheduler, AlarmReceiver,
                         NetworkWatcher, Power, StartReceiver
  app.plico.notify       Notifier, ActionReceiver
  app.plico.html         HtmlSanitizer, LinkCleaner, Idn, Psl, SenderCheck, RemoteFetcher, Mailto,
                         ListUnsubscribe, SenderLogos, LogoRules
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
enum class TlsMode { IMPLICIT, STARTTLS }                              // there is no cleartext value
data class Endpoint(val host: String, val port: Int, val tls: TlsMode)  // host: ASCII, lower case, no trailing dot
class MailSocket : MailStream {
    companion object {
        fun open(host: String, port: Int, hooks: SocketHooks, tls: TlsMode = TlsMode.IMPLICIT,
                 connectTimeoutMs: Int = 20_000, readTimeoutMs: Int = 60_000,
                 factory: javax.net.ssl.SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory): MailSocket
    }
    val input: java.io.InputStream     // buffered
    val output: java.io.OutputStream   // buffered; caller flushes
    var readTimeoutMs: Int             // 0 = infinite (IDLE)
    val encrypted: Boolean             // true once TLS is up (always for implicit TLS)
    fun startCompression()             // RFC 4978 raw DEFLATE both ways; every flush() = SYNC_FLUSH
    fun startTls()                     // after the server accepted STARTTLS
    override fun close()               // idempotent, safe from any thread
}
class StartTlsException(message: String) : java.io.IOException(message)
class TlsHandshakeException(cause: javax.net.ssl.SSLException) : java.io.IOException(cause.message, cause)
```
Every connection uses TLS: implicit TLS or STARTTLS, and STARTTLS is always required. There is no cleartext
mode, no "STARTTLS if available" and no fallback. The protocol clients check `encrypted` before they send a
password or a token (§4.4, §4.7).

Implicit TLS (`apis AF9`): `val s = factory.createSocket() as SSLSocket; s.connect(InetSocketAddress(host, port),
connectTimeoutMs)` (keeps the hostname for SNI and CT); `hooks.onConnected(s)`; then `secure()`: enabled protocols
`TLSv1.3, TLSv1.2` only; `sslParameters.endpointIdentificationAlgorithm = "HTTPS"`; SNI = host;
`startHandshake()`; `hooks.verifyHostname` must be true, else close and throw `SSLPeerUnverifiedException`.

STARTTLS:
- `open`: a plain `Socket`, connect, `hooks.onConnected`. The read timeout is at most 20 s until TLS is up (also
  when `readTimeoutMs` is 0): with STARTTLS on port 993 or 465 the server waits for a ClientHello and sends no
  greeting, so the client must not wait without end. `ImapClient` and `SmtpClient` turn a read timeout before the
  switch (the greeting, SMTP EHLO, the answer to STARTTLS) into `PlaintextTimeoutException`, a
  `SocketTimeoutException`; only setup reads it as a wrong security setting (§13). A timeout after the switch stays
  a plain `SocketTimeoutException`.
- `startTls()`: refused after TLS or compression. When any byte follows the server's reply (pipelining, which
  STARTTLS forbids, or an injection), it throws `StartTlsException` and closes the socket. Else
  `factory.createSocket(raw, host, port, true)` and the same `secure()` checks as implicit TLS; new buffers on
  the TLS socket (the plaintext buffers are dropped unread); the TLS socket becomes the current socket, so that
  `readTimeoutMs`, `startCompression` and `close` use it. Bytes that arrive after the check go into the TLS layer
  and fail the handshake. Any failure closes the raw socket.

`secure()`: an `SSLException` from the handshake without a `CertificateException`/`CertPathValidatorException`
in its cause chain becomes `TlsHandshakeException` (cause kept), for example "Unrecognized SSL message" from a
plaintext server on the port. Certificate failures stay as they are, so the app still finds them in the cause
chain (§8.3, §14).

`open` refuses a host that is empty, not ASCII or has spaces or control characters (`IllegalArgumentException`);
callers convert IDN names to ASCII first.

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
    fun startTls()                                         // STARTTLS, the switch, capabilities cleared (§4.4)
    val isOpen: Boolean                                    // false after close() or an error that closed it
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
- The log callback gets only tags, command verbs, statuses and response-code names; never arguments, so no
  credentials, mailbox names or addresses. A SASL answer is logged as `<sasl response>`, `LOGIN` as `LOGIN` only.
- `startTls()`: `STARTTLS`, then `MailStream.startTls()` (fail closed), a new reader on the TLS stream, and empty
  capabilities. The reset comes after the tagged OK, because that OK can carry a `[CAPABILITY]` code, and
  capabilities from before TLS are not trusted.
- Fail closed: an error before the tagged response (or `close()`) marks the connection broken and closes
  it; later calls throw `IOException("Connection closed")`, and `isOpen` is false. Errors that close it: read
  error or timeout, a reader limit, a protocol error, an exception from a callback or hook. A tagged NO or BAD
  ends the command and keeps the connection. `done()` never throws and never waits for a command write
  (separate IDLE lock).
- Read timeout 60 s during commands; 0 during IDLE from `+` until the tagged OK (the app's 20 s refresh
  watchdog closes the socket if the OK never comes).
- EXISTS from SELECT/EXAMINE also reaches `onUnsolicited`; unsolicited FETCH responses with a UID during
  `uidFetch` also reach `each`. Callers must be idempotent.

### 4.4 `imap.ImapClient`
```kotlin
class ImapClient(val conn: ImapConnection, val host: String = "") : Closeable {   // host = endpoint host ("" only in tests)
    companion object {
        fun connect(endpoint: Endpoint, hooks: SocketHooks, log: (String) -> Unit = {}): ImapClient   // always encrypted
    }
    val caps: Set<String>                                      // effective: advertised + RFC 9051 base set for rev2-only servers
    val isGmail: Boolean                                       // "X-GM-EXT-1" in caps
    fun authenticatePassword(user: String, password: String)   // PLAIN (SASL-IR or continuation), else LOGIN
    fun authenticateXoauth2(user: String, accessToken: String) // IR inline or after '+'; on error challenge send empty line
    fun requireCapabilities()                                  // CAPABILITY if needed; neither IMAP4rev1 nor IMAP4rev2 -> ImapMissingCapabilityException
    fun enable(vararg ext: String): Set<String>                // nothing is sent without ENABLE
    fun namespace(): Namespaces?                               // RFC 2342, once per connection; null without NAMESPACE or on NO/BAD
    fun id(fields: List<Pair<String, String>>)                 // RFC 2971; the reply (also NO/BAD) is ignored
    fun compress()                                             // when COMPRESS=DEFLATE
    fun list(): List<MailboxInfo>                              // LIST "" "*" [RETURN (SPECIAL-USE)]
    fun status(mailbox: String): MailboxStatus                 // MESSAGES UIDNEXT UIDVALIDITY (+ HIGHESTMODSEQ when CONDSTORE)
    fun listStatus(): Map<String, MailboxStatus>               // LIST "" "*" RETURN (STATUS (…)) (LIST-STATUS); incomplete entries left out
    val selected: String?
    fun select(mailbox: String): SelectResult
    fun examine(mailbox: String): SelectResult
    fun uidSearch(criteria: Command): UidSet                   // UID SEARCH RETURN (ALL) [CHARSET UTF-8] <criteria>; plain without ESEARCH
    fun uidSearchCount(criteria: Command): Long                // UID SEARCH RETURN (COUNT) …; plain: the number of hits
    fun uidSearchMax(criteria: Command): Long?                 // UID SEARCH RETURN (MAX) … (backfill step); plain: the highest hit
    fun fetch(seqRange: String, items: String, each: (FetchItem) -> Unit)                 // by sequence (first-sync window)
    fun uidFetch(uids: UidSet, items: String, changedSince: Long? = null, each: (FetchItem) -> Unit)
    fun uidStoreFlags(uids: UidSet, add: Boolean, flags: List<String>)    // .SILENT
    fun uidStoreLabels(uids: UidSet, add: Boolean, labels: List<String>)  // X-GM-LABELS.SILENT
    fun uidMove(uids: UidSet, dest: String): Map<Long, Long>   // COPYUID mapping old -> new; COPY fallback without MOVE
    fun uidExpunge(uids: UidSet, guard: Boolean = true): Boolean   // false = the guard skipped the EXPUNGE
    fun append(mailbox: String, flags: List<String>, message: ByteArray): Long?   // APPENDUID, else null
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
data class Namespace(val prefix: String, val delimiter: String?)
data class Namespaces(val personal: List<Namespace>, val otherUsers: List<Namespace>, val shared: List<Namespace>)
```
- Mailbox names are modified UTF-7 on the wire. Exception: a server that advertises `IMAP4rev2` without
  `IMAP4rev1` (the same test as the base set below) uses UTF-8 names (RFC 9051 §5.1) and need not know modified
  UTF-7, so there names go out and come in as UTF-8 (non-ASCII as a literal; `&` as it is). A server with both stays
  in IMAP4rev1 mode (Plico never sends `ENABLE IMAP4rev2`). Never `ENABLE UTF8=ACCEPT`. Gmail `X-GM-LABELS` values
  are astrings in modified UTF-7; system labels start with `\`.
- `UidSet`: sorted ranges; `toString()` = `1:5,8,10:12`; `*` parses to Long.MAX_VALUE; split into
  commands of at most 500 ranges. A UID out of 1..2^32-1 is malformed; `toList()` of more than 1,000,000 UIDs is a
  protocol error (a server range cannot fill the heap).
- Search with non-ASCII text: `UID SEARCH CHARSET UTF-8 <criteria with literal>` (`protocol PS16`).
- No VANISHED (QRESYNC is never enabled) and no `~{n}` literals (BINARY is not used).

**Connect.** `connect(endpoint)` reads the greeting. Implicit TLS: as before (a `PREAUTH` greeting is allowed).
STARTTLS: a `PREAUTH` greeting fails (`StartTlsException`: it would skip the upgrade). `STARTTLS` is always sent;
the capabilities before TLS are not trusted, so neither a missing `STARTTLS` nor `LOGINDISABLED` changes this. A
tagged NO or BAD closes the connection with `StartTlsException`. After the switch, `CAPABILITY` is asked again.
Any failure closes the stream; a client that `connect` returns is always encrypted.

**Capabilities.** Only `IMAP4rev1` or `IMAP4rev2` is required. IDLE, MOVE, UIDPLUS, ESEARCH, CONDSTORE, ENABLE,
SPECIAL-USE, NAMESPACE and LIST-EXTENDED are optional, each with a fallback below (IDLE: the account polls,
§8.3). A server that advertises `IMAP4rev2` without `IMAP4rev1` gets the RFC 9051 base set in `caps`: IDLE, MOVE,
UIDPLUS, ESEARCH, NAMESPACE, ENABLE, SPECIAL-USE, LIST-EXTENDED, LIST-STATUS, SASL-IR, UNSELECT (literals still
use only the advertised set). `requireCapabilities()` keeps the "CAPABILITY after login when not sent" step.

**Sign-in.** Both methods check `encrypted` first: no password or token byte goes out before TLS.
- `authenticatePassword`: when the capabilities are still unknown (the Gmail and Exchange greetings have none),
  `CAPABILITY` first (one more round trip). Then `AUTH=PLAIN` + `SASL-IR` → `AUTHENTICATE PLAIN <ir>` (Riseup);
  `AUTH=PLAIN` without SASL-IR → `AUTHENTICATE PLAIN`, the response after `+`; no `AUTH=PLAIN` and no
  `LOGINDISABLED` → `LOGIN <user> <password>` as strings (quoted, or literals; LITERAL+ is used); else
  `ImapMissingCapabilityException(AUTH=PLAIN)` before the password is sent.
- `authenticateXoauth2`: the initial response goes inline when `SASL-IR` is advertised or the capabilities are
  unknown (the Gmail form, unchanged); else bare `AUTHENTICATE XOAUTH2` and the response after the first `+`.
- Continuations: the first `+` gets the response when it did not go inline; every later `+` is an error report
  and gets `""` (XOAUTH2) or `*` (PLAIN cancel), then the server sends NO.

**Fallbacks** (the servers of Riseup and of Gmail advertise MOVE, UIDPLUS, ESEARCH, LIST-EXTENDED and SPECIAL-USE,
so their commands do not change):
- `list()`: `RETURN (SPECIAL-USE)` only with both SPECIAL-USE and LIST-EXTENDED (RETURN options need
  LIST-EXTENDED); a plain LIST still carries the special-use attributes.
- No MOVE: per chunk `UID COPY` (COPYUID from the tagged OK) and `UID STORE +FLAGS.SILENT (\Deleted)`, then
  the caller's expunge over the whole set (by default the guarded `uidExpunge`; `Ops` passes its own, §7.4). Not
  atomic: a retry after a lost connection can copy twice. Gmail has MOVE, so this never runs on All Mail.
- `uidExpunge(uids, guard, allowed)`: with UIDPLUS `UID EXPUNGE` per chunk (also for `guard = false`). Without
  UIDPLUS only a plain `EXPUNGE` exists, which removes every `\Deleted` message of the mailbox. With `guard`, the
  client first sends `UID SEARCH DELETED`; when a hit is in neither `uids` nor `allowed` (the caller's own
  `\Deleted` messages that an earlier skipped expunge left), it sends no EXPUNGE and returns false. Other
  clients (Outlook in IMAP mode, Roundcube) keep struck-through `\Deleted` mail that the user can still
  undelete; Plico never removes it. The rows are already gone locally, and `message.deleted` hides the messages
  that stay on the server (§7.2). `guard = false` (plain EXPUNGE) only for Empty Trash/Spam and permanent deletes
  inside Trash or Spam (§7.4).
- No ESEARCH: plain `UID SEARCH [CHARSET UTF-8] <criteria>`. The parser reads `* SEARCH` and `* ESEARCH` lines
  (an IMAP4rev2-only server answers with ESEARCH): ALL = the union of the lines, COUNT = their sizes added,
  MAX = the highest UID. Such an answer lists every hit, so callers search in UID windows (§7.2, §7.7).
- SELECT or EXAMINE without UIDNEXT: `FETCH * (UID)` + 1, or 1 for an empty mailbox. This is a lower bound (a
  deleted last message can have had a higher UID); the sync corrects it with STATUS (§7.2 step 4).
- No ENABLE: `enable()` sends nothing. No NAMESPACE: `namespace()` is null.
- `namespace()` reads `* NAMESPACE (("INBOX." ".")) NIL NIL` and the full form with other users' and shared
  groups; `NIL` = an empty group; prefixes are decoded like mailbox names. The answer (also null) is kept for the
  connection.
- `id()`: `ID ("name" "Mail")` only, never a version, an OS or device data. Only for NetEase servers (§7.1).

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
- `parseBodyStructure` never throws: missing or NIL fields take RFC 2045 defaults; a multipart nested deeper
  than 100 levels keeps no children.
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
object Addresses { fun parseList(value: String): List<Address>; fun format(list: List<Address>, encode: Boolean = true): String
    fun isValidAddrSpec(s: String): Boolean /* = AddrSpec.isValid */ }
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
- `Addresses.format`: names pass `HeaderSafety.clean` and are quoted when they hold specials. `encode = true`
  (headers): RFC 2047 for non-ASCII names. `encode = false`: names stay as they are; this is the stored
  `*_list` form that `parseList` reads back, and the UI form.
- `AddrSpec.isValid`: strict; no CR, LF, `<`, `>`, unquoted space, controls; RFC 5321 length limits.

### 4.7 `smtp.SmtpClient`
```kotlin
class SmtpClient(stream: MailStream, log: (String) -> Unit = {}, val host: String = "") : Closeable {   // host = endpoint host
    companion object { fun connect(endpoint: Endpoint, hooks: SocketHooks, log: (String) -> Unit = {}): SmtpClient } // MailSocket + start(endpoint.tls)
    fun start(tls: TlsMode = TlsMode.IMPLICIT)           // greeting + EHLO [127.0.0.1]; STARTTLS: + STARTTLS + EHLO again
    val extensions: Map<String, String>; val maxSize: Long?
    val authMechanisms: Set<String>                      // upper case, from "AUTH A B" and the legacy "AUTH=A B" lines
    fun authPassword(user: String, password: String)     // PLAIN, else LOGIN, else SmtpAuthUnavailableException
    fun authXoauth2(user: String, accessToken: String)   // on 334 challenge send empty line, then expect 5xx
    fun send(from: String, recipients: List<String>, message: ByteArray)
    fun quit()
}
class SmtpException(val code: Int, val enhanced: String?, message: String, val stage: Stage) : java.io.IOException(message) {
    enum class Stage { CONNECT, EHLO, STARTTLS, AUTH, MAIL, RCPT, DATA, FINAL }
    val transient: Boolean get() = code in 400..499     // RCPT rejections use SmtpRecipientException
}
class SmtpRecipientException(val rejected: Map<String, String>) : java.io.IOException()
class SmtpAuthUnavailableException : java.io.IOException("No usable AUTH mechanism")
```
- EHLO argument is always `[127.0.0.1]`. No CHUNKING, DSN or PIPELINING.
- `start(STARTTLS)`: 220 greeting, `EHLO`, `STARTTLS`. Any reply other than 220 → `StartTlsException`; the
  stream is closed, and no AUTH and no fallback follow. Then `stream.startTls()` (bytes after the 220 fail it,
  §4.1) and a second `EHLO`. Only the EHLO after TLS sets `extensions`, `maxSize` and `authMechanisms`
  (RFC 3207 §4.2).
- `authPassword`: `encrypted` is checked first. `AUTH PLAIN <ir>` when PLAIN is listed; else `AUTH LOGIN`
  (334, user in base64, 334, password in base64, 235); no usable mechanism (also no AUTH line) →
  `SmtpAuthUnavailableException` before the password is sent. A wrong password → `SmtpException` (535, AUTH).
- `authXoauth2`: `encrypted` is checked first. `AUTH XOAUTH2 <ir>` when the line has at most 510 octets
  (Gmail); a longer line (Microsoft tokens are 1.5–3.5 KB, RFC 4954 §4) sends bare `AUTH XOAUTH2`, waits for 334
  and then sends the response.
- `send`: at least one recipient; every address must pass `isValidAddrSpec` before any byte is written, else
  `IllegalArgumentException` (its text names no address). `MAIL FROM:<..>`, plus `SIZE=n` when SIZE is
  advertised and `SMTPUTF8` when needed. All `RCPT TO` lines are sent, then: any 5xx, or 4xx with an address error code
  (4.1.x, e.g. Riseup `450 4.1.2` for a mistyped domain) → `RSET`, no DATA, `SmtpRecipientException` with every
  refused address (`protocol PS15`); else another 4xx → `RSET`, transient `SmtpException(RCPT)` with the first
  such reply; 421 → transient at once, without `RSET`. A refused DATA → `RSET` (not after 421). `DATA`,
  dot-stuffing, `CRLF.CRLF`. The reply to the final dot uses a 10-min read timeout; any I/O error there throws
  `SmtpUncertainException` ("may have been sent").
- Replies: one code per reply, lines ≤ 8 KB, ≤ 200 lines, else `ProtocolException`.
- Logging (`privacy PS18`): only command verbs and reply codes; never arguments, 334 payloads or the answers
  (SASL answers, also of AUTH LOGIN, are logged as `<sasl response>`).

## 5. Data (`data`)

Framework SQLite (`SQLiteOpenHelper`, WAL, `PRAGMA foreign_keys=ON`). One `Db` class: `query`,
`tx {}`, `changes: SharedFlow<Set<String>>` (table names) that drives `Flow` queries. Schema v5 = schema v1
(below) + "Migration 1 → 2" + "Migration 2 → 3" + "Migration 3 → 4" + "Migration 4 → 5" (end of this section).
Schema v1:

```sql
CREATE TABLE account(
  id INTEGER PRIMARY KEY AUTOINCREMENT, provider TEXT NOT NULL,      -- 'riseup' | 'gmail' | 'microsoft' | 'other'
  email TEXT NOT NULL UNIQUE, sender_name TEXT NOT NULL DEFAULT '', label TEXT NOT NULL,   -- label default: provider brand (+ n)
  color INTEGER NOT NULL DEFAULT 0,
  auth TEXT NOT NULL,                       -- 'password' | 'oauth' | 'device'
  secret BLOB, device_account TEXT,
  body_days INTEGER NOT NULL DEFAULT 30,    -- 0 = all mail offline
  push_mode INTEGER NOT NULL DEFAULT 0,     -- 0 instant, 1 instant on Wi-Fi/charging + poll on mobile data, 2 poll
  poll_minutes INTEGER NOT NULL DEFAULT 15,
  gmail_categories TEXT NOT NULL DEFAULT 'primary',
  archive_folder INTEGER,                   -- not Gmail: chosen archive folder id (NULL = auto)
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
  `X-GM-LABELS`. Every list = `message JOIN message_folder WHERE folder_id IN (…) AND hidden = 0 AND
  deleted = 0 ORDER BY date DESC`. "Starred" is virtual (`flagged = 1`). Unified inbox = all `role = 'inbox'`.
- Rows whose `message_id` equals a local `draft.message_id` are hidden from every list (`protocol PS7`).
- `message.deleted = 1`: the server flag `\Deleted` (every server except Gmail, §7.2). Such a row is stored and
  never shown: lists, counts, unread counts, search and notification candidates all filter `deleted = 0`.
- Unread counts are computed (`GROUP BY folder_id` over `seen = 0 AND hidden = 0 AND deleted = 0`), never
  stored (`simplicity EM10`).
- Servers live in the account row (`imap_host imap_port imap_tls smtp_host smtp_port smtp_tls login idle`,
  migration below). `imap_tls`/`smtp_tls`: 0 implicit TLS, 1 STARTTLS; any other value reads as implicit TLS,
  never as cleartext. `login` NULL = the email address. Discovery runs once at setup and its result is stored;
  a connection never looks up servers again (RFC 8314 §5.1; DNS is not authenticated).
- Server change (`AccountDao.setAuth(id, auth, secret, deviceAccount, ServerUpdate(servers, idle), files)`, from
  "Sign in again", §13): the sign-in and the servers are written in one transaction. When the IMAP host or port
  changes, the same transaction deletes the account's folder rows (cascade: messages, memberships, bodies,
  attachment rows, FTS through the trigger) and the ops except `append` (they name a role and a draft, so a pending
  Sent copy or draft upload goes to the new server), sets `draft.on_server = 0`, and clears `archive_folder`,
  `quota_used`, `quota_limit` and `server_keepalive_s`, so that a fresh sync starts. Then the attachment dirs of
  the deleted messages are deleted. The session of the old servers stops before the write.
- `AccountDao.setIdle`: the session stores `idle = 0` when the server has no IDLE (§7.1).
- Upserts use `INSERT … ON CONFLICT DO UPDATE` or `INSERT OR IGNORE`; never `REPLACE` (cascade) (`protocol PS10`).
- FTS rows: `INSERT OR REPLACE INTO message_fts(rowid, …)`.
- Account removal: stop the session, revoke the OAuth grant (Google; Microsoft has no revoke endpoint for
  public clients, so the remove dialog names the pages where the user ends the grant, §6.4), cascade, delete
  `outbox/` and `att/` dirs of the account, `INSERT INTO message_fts(message_fts) VALUES('optimize')`,
  `PRAGMA wal_checkpoint(TRUNCATE)` (`privacy PS8`), delete the sender-logo cache (§12); contacts and trusted
  senders go with the last account. Its notifications are cancelled; the push engine deletes the channels of
  accounts that are gone (`removeStaleChannels`).
- `DraftState`: `EDITING` 0, `QUEUED` 1, `SENDING` 2, `FAILED` 3, `SENT` 4, `UNCERTAIN` 5 (failed, but may have
  been sent: no reply to the final dot, or the process died while sending). FAILED and UNCERTAIN are both
  "failed" (Edit, Retry); the UI checks the state, never the error text.
- Only `data` writes SQL. The DAOs (`AccountDao FolderDao MessageDao DraftDao OpDao ContactDao TrustDao`) also
  hold the rules that more than one area needs, e.g. `FolderDao.abilities(account)` (Delete/Spam, §7.3) for the
  list, the message screen and the notification actions.

`Vault`: AES-256-GCM key `plico.vault.v1` in AndroidKeyStore (StrongBox if available, else TEE),
no user-auth or unlocked-device binding. Ciphertext = 12-byte IV + GCM output.

`Prefs` (no secrets): swipe actions, undo-send seconds, remote-content default, confirm links, dark
email, app lock + timeout, hide in recents, block screenshots, incognito keyboard, hide notification
content, the message-list look (`mark_unread` true, `avatar_colors` true, `account_dots` true, `sender_logos` false, `logo_source`
`DUCKDUCKGO | WEBSITE | GOOGLE`, default DuckDuckGo; §12, §13), the Google OAuth client ID and the Microsoft
(Entra) client ID (empty = that sign-in is hidden; debug builds preset them from `local.properties`
`plico.googleClientId` / `plico.microsoftClientId`, release builds never contain one), push paused flag,
learned keepalive intervals (≤ 16 keys, LRU), pending OAuth `{issuer, state, verifier, email, createdAt}` plus
the code or the error of the redirect (single use, 10 min; a stale entry is dropped at process start, on the
main thread; an entry without `issuer` reads as Google), the Gmail folder-size note per account (deleted with the
account). Enums are stored by name; an unknown name reads as the default. `Prefs` implements `KeyValueStore`
(`get`/`put` of raw strings) for the components that own their format.

`Files` (`privacy PS1`): one helper for every on-disk name: `<base>/<ownerId>/<itemId>` or
`<base>/<ownerId>/<safeName>`; `safeName` replaces `/ \ :` NUL, C0/C1 and bidi controls
(U+202A–202E, U+2066–2069) with `_`, strips leading dots, max 100 chars, fallback `attachment`;
`canonicalPath` must start with `base.canonicalPath + "/"`. The original name lives only in the DB.
`outboxDraftIds()` lists the outbox dirs, so that process start deletes the ones without a draft row.
`logoDir()` = `cacheDir/logos/`; `logo(hash, ext)` = `cacheDir/logos/<hash>.<ext>` through the same `inside()`
check, where `hash` is the hex SHA-256 of a domain (never a name from the network, §12).

Contacts are learned from sent recipients and from senders the user replied to. No contacts permission.

### Migration 1 → 2

`Schema.MIGRATIONS[i]` upgrades version i + 1 to i + 2 (`onUpgrade`, in one
transaction); a fresh database runs v1 and then every migration, so it gets the same statements. Hosts are
literals, never `Providers` values. The default ports and TLS mode (993/465, implicit TLS) are those of Riseup
and Gmail. The SQL comments below are Kotlin comments in `Schema.kt`: ALTER TABLE ADD COLUMN copies a trailing
`--` comment into the stored CREATE TABLE, and SQLite then refuses the statement.

```sql
ALTER TABLE account ADD COLUMN imap_host TEXT NOT NULL DEFAULT '';
ALTER TABLE account ADD COLUMN imap_port INTEGER NOT NULL DEFAULT 993;
ALTER TABLE account ADD COLUMN imap_tls INTEGER NOT NULL DEFAULT 0;      -- 0 implicit TLS, 1 STARTTLS (always required); no cleartext value
ALTER TABLE account ADD COLUMN smtp_host TEXT NOT NULL DEFAULT '';
ALTER TABLE account ADD COLUMN smtp_port INTEGER NOT NULL DEFAULT 465;
ALTER TABLE account ADD COLUMN smtp_tls INTEGER NOT NULL DEFAULT 0;
ALTER TABLE account ADD COLUMN login TEXT;                               -- NULL = the email address
ALTER TABLE account ADD COLUMN idle INTEGER NOT NULL DEFAULT 1;          -- 0 = the server has no IDLE: the account polls
ALTER TABLE message ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;       -- server flag \Deleted (not Gmail): the row is not shown
UPDATE account SET imap_host = 'mail.riseup.net', smtp_host = 'mail.riseup.net' WHERE provider = 'riseup';
UPDATE account SET imap_host = 'imap.gmail.com', smtp_host = 'smtp.gmail.com' WHERE provider = 'gmail';
```

### Migration 2 → 3

The column keeps the UIDs that Plico flagged `\Deleted` in a mailbox and that a guarded
EXPUNGE left on the server (§7.4), as a UID set (`3:5,9`). They are Plico's own messages, so they never stop a
later guarded EXPUNGE. A plain EXPUNGE of the mailbox and a resync (UIDVALIDITY change) clear it.

```sql
ALTER TABLE folder ADD COLUMN own_deleted TEXT;                          -- UIDs that Plico flagged \Deleted and a guarded EXPUNGE left on the server; NULL = none
```

### Migration 3 → 4

`attachment.message_id` is the child key of `ON DELETE CASCADE`. Without an index, each
deleted message scans the whole attachment table (resync, expunge, account removal), and so does each read of
the attachments of one message.

```sql
CREATE INDEX attachment_message ON attachment(message_id);              -- the child key of ON DELETE CASCADE
```

### Migration 4 → 5

`Schema.VERSION` is 5. `trusted_sender.verified` keeps where the user set the trust (§11.4, `PLICO-PRIV-003`):
1 = on a VERIFIED message (Gmail with `dmarc=pass`), where the "A sender can be faked" note does not show; such a
trust counts only on VERIFIED messages. 0 = on a FROM_ONLY message, where the note was on screen; it counts on
both. The rows of before get 0, so that they work as before. A trust set by From only stays 0 when the user also
sets it on a VERIFIED message (`min`).

```sql
ALTER TABLE trusted_sender ADD COLUMN verified INTEGER NOT NULL DEFAULT 0;   -- 1 = set on a VERIFIED message: honoured only there; 0 = set by From only (rows of before stay 0)
```

## 6. Providers, discovery and sign-in (`account`)

### 6.1 Provider kinds and traits
`Provider` has four values: `GMAIL`, `MICROSOFT`, `RISEUP`, `OTHER`. The values name kinds of behaviour, not
brands: only these four behave differently in code. A provider of the table (Yahoo, Fastmail, …, §6.2) is
`OTHER` with servers from the table. An unknown stored id reads as `OTHER`, never a crash. The user never selects
a provider: setup asks only for the address (§13), and no text says that the app is for some providers.

`Providers.traits(provider)` holds the behaviour; the servers are in the account row (§5).

| | Riseup | Gmail | Microsoft | Other servers |
|---|---|---|---|---|
| Built-in servers | `mail.riseup.net:993` / `:465` | `imap.gmail.com:993` / `smtp.gmail.com:465` | `outlook.office365.com:993` / `smtp-mail.outlook.com:587` STARTTLS (Outlook.com, Hotmail, Live) or `smtp.office365.com:587` STARTTLS (Microsoft 365) | none: the table, discovery or the user |
| Login | full address | full address (OAuth: the id_token email) | address, or `preferred_username` when it differs (§6.5) | address, or the user name of the table, autoconfig or the user |
| Auth | PLAIN (SASL-IR) | PLAIN (app password) or XOAUTH2 | XOAUTH2 (browser) | PLAIN or LOGIN over TLS (password or app password) |
| Max message (`maxMessageBytes`) | 25,600,000 bytes | 35,651,584 bytes | 25,000,000 bytes | 25,000,000 bytes |
| Sent copy (§7.5) | client APPENDs | never (the server saves it) | never (the server saves it) | client APPENDs after 60 s and a check; never for Yahoo and AOL |
| Keepalive start (§8.3) | 15 min | 9 min | 15 min | 15 min |
| Keepalive scope (§8.3) | `riseup` | `gmail` | IMAP host | IMAP host |
| Notes | IPv4 only; `Spam` name is fixed; Trash 21 d / Spam 7 d auto-delete; settings at account.riseup.net | session ~24 h (password) / token lifetime (OAuth); 15-connection limit; Send-as for non-Google addresses ends Jan 2027 | authority `common`; no revoke endpoint; SMTP AUTH can be off for a mailbox | IDLE, MOVE, UIDPLUS, ESEARCH, CONDSTORE are optional (§4.4) |

- The size cap is an upper limit: the live SMTP `SIZE` (at send) and `APPENDLIMIT` (at APPEND) can only lower it.
  25 MB keeps the two in-memory copies of a message in `Sender` far from the heap limit.
- The code paths of Gmail accounts and of Riseup accounts are keyed off `account.provider` (`isGmail`,
  `RISEUP`) as before. `Account.generic` (Microsoft and other servers) gates the code that only those servers
  need: the NAMESPACE call, the personal root for default names (§7.2, §7.4) and the SELECT before a draft
  APPEND (§7.6).
- Default account label = the brand of the kind: "Gmail", "Outlook", "Riseup", "Mail"; the next account of
  the same kind gets " 2", " 3" and so on. Never the domain: the label can show on the lock screen, and a custom
  domain identifies the user (`privacy PS11`).

### 6.2 Provider table (`ProviderTable`)
The built-in table of providers. The facts come from the documentation of each provider (no text is copied from
other mail clients); facts marked "live-check" in the code are in §16.
- Rows: Riseup; Gmail; Outlook (Outlook.com, Hotmail, Live, MSN and their country domains); Microsoft 365 (every
  `*.onmicrosoft.com` address, and custom domains by MX); Yahoo; AOL; iCloud; Fastmail; Zoho (6 regions) and Zoho
  custom domains ("pro" servers, 4 regions, by MX); GMX (2 rows); WEB.DE; mailbox.org; Posteo; Disroot; Infomaniak;
  Yandex; Mail.ru; NetEase 163, 126, Yeah; Libero; Virgilio; TIM; Aruba; Seznam; Centrum; Atlas; Volny.
  Refusals: Proton Mail (Bridge only), Tuta and HEY (no IMAP).
- Ports 993 and 465 are implicit TLS; 143 and 587 are STARTTLS (TIM IMAP, WEB.DE SMTP, iCloud SMTP).
- Per row: `notes` shown before the password (`APP_PASSWORD`, `ENABLE_IMAP`), `mxNotes` for an MX match only
  (`ICLOUD_LOGIN`: a custom domain on iCloud signs in with the @icloud.com address), `login = LOCAL_PART`
  (Disroot), `serverSavesSent` (Yahoo, AOL: never APPEND to Sent), `needsId` (NetEase: SELECT is refused until the
  client sends IMAP `ID`; §7.1).
- `byDomain(domain)`: exact match of the ASCII lower-case domain. `byMx(host)`: MX host suffix match
  (`host == s` or ends with `.s`); the first row wins (`olc.protection.outlook.com` is Outlook,
  `mail.protection.outlook.com` is Microsoft 365).
- `isMailboxDomain(domain)`: a domain of the table (no sender logo, §12).

**Pinned hosts.** The hosts of Google, Microsoft and Riseup are pinned: such an account always uses the built-in
servers, and a pair with one such host and one other host is refused. So a spoofed SRV record, a spoofed
autoconfig file or a hostile server never gets a Google or Microsoft token or app password.
- Host sets: Gmail `imap.gmail.com smtp.gmail.com imap.googlemail.com smtp.googlemail.com`; Microsoft
  `outlook.office365.com outlook.office.com imap-mail.outlook.com smtp-mail.outlook.com smtp.office365.com`;
  Riseup `mail.riseup.net`. The other names of a set are accepted as input and then replaced.
- `providerOf(servers)`: that provider when both hosts are in its set; `OTHER` when neither host is in any set;
  null (refused) for a mixed pair.
- `pinned(provider, email)`: the built-in servers; Microsoft: the Outlook.com servers for the Outlook consumer
  domains, else the Microsoft 365 servers.
- A Google Workspace domain is stored as `GMAIL` only when its hosts are Google's (found by MX, autoconfig or
  SRV, then pinned); a Microsoft 365 domain as `MICROSOFT` the same way. The capability `X-GM-EXT-1` never makes
  an account a Gmail account: the connection test and the session refuse a Gmail server for any other provider.

**Typo check** (`suggest(domain)`, before discovery): a table domain of 8 or more characters at an edit distance
of 1 (optimal string alignment: a swap of two neighbours counts 1), for example `gmial.com`, `gmail.co`,
`gmail.con` → `gmail.com`. Not for a swap of a two-letter country label (`hotmail.be` and `outlook.at` are real),
not for a suffix far from the table suffix (`outlook.jp`, `hotmail.com.br`, `yahoo.co.jp`), not for real domains
one edit from a table domain (`ProviderTable.NOT_TYPOS`: `mail.com` and `email.com` of the mail.com service near
`gmail.com`, `olive.com` and `alive.com` near `live.com`), and never for shorter table domains (`me.com`,
`bk.ru`). Setup shows "Did you mean …?"; Next again with the same address continues.

### 6.3 Discovery (`account.discovery`)
`Discovery.discover(email)` runs once at setup. No third party is asked: the built-in table, then the domain's
own autoconfig over HTTPS and DNS through the system resolver, then manual entry. Only the domain is used
(`normalizeHost` of the part after `@`); the local part never leaves the phone.

Order:
1. `ProviderTable.byDomain`: a refusal → `Refused`; else `Found(provider, servers, TABLE, notes)`. No network
   request for a table domain.
2. At the same time: both autoconfig URLs (`https://autoconfig.<d>/mail/config-v1.1.xml`,
   `https://<d>/.well-known/autoconfig/mail/config-v1.1.xml`), DNS SRV `_imaps._tcp.<d>`, `_imap._tcp.<d>`,
   `_submissions._tcp.<d>`, `_submission._tcp.<d>`, and the MX of `<d>`. The whole step has 10 s; then the guess.
   The results are used in this priority:
   a. **Autoconfig** (the first URL with a usable file; a file with a mixed pair falls through to the next URL):
      the provider is `providerOf(servers)`. When the lowest MX is a Google or Microsoft table row of another
      provider, the MX result wins (an old cPanel file after a move to Workspace or Microsoft 365). Else
      `Found(provider, pinned servers or the file's servers, AUTOCONFIG)`. A file from the domain itself over
      HTTPS is a statement of the domain owner; the password page still shows its hosts.
   b. **SRV for both protocols**, both targets in the site of the domain (same PSL registrable domain), `OTHER`
      → `Found(OTHER, SRV)`.
   c. **The lowest MX** matches a table row (`byMx`): a refusal → `Refused`; Google or Microsoft →
      `Found(pinned servers, MX)` (the hosts come from the table, so a spoofed MX can only lead to the real
      servers of Google or Microsoft); an `OTHER` row → `NotFound(its servers, MX, notes + mxNotes)`: the user
      checks the values on the server page.
   d. **SRV for both protocols** with a target outside the site: a pinned provider → `Found(pinned servers,
      SRV)`; `OTHER` → `NotFound(the SRV servers, SRV)`; a mixed pair is dropped.
   e. **SRV for one protocol**: `NotFound(guess)` with that server filled in only when it is in the site.
3. `NotFound(guess)`: `imap.<d>:993` and `smtp.<d>:465`, implicit TLS.

`NotFound` opens the server page (§13) with its values and a text by source: nothing found, or "found through
DNS, make sure that they are correct" (SRV, MX).

SRV choice per protocol: the implicit-TLS label when it has a usable record, else the STARTTLS label; lowest
priority first, then the highest weight, then answer order (fixed, not the random pick of RFC 2782); a `.` target
means "not offered". Ports 993 and 465 are always implicit TLS (Disroot publishes 465 as `_submission`).

**Autoconfig file** (`Autoconfig.parse`, the Thunderbird `config-v1.1.xml` format):
- Refused before parsing: more than 64 KiB, `<!DOCTYPE` or `<!ENTITY` in any case, a NUL character. A UTF-8 BOM
  is removed. The bytes are decoded once as UTF-8, and the parser reads that same String, so it cannot find
  another encoding.
- Platform DOM parser (`DocumentBuilderFactory`): namespaces off, entity expansion off, and
  `FEATURE_SECURE_PROCESSING` and `disallow-doctype-decl` when the platform accepts them (try/catch).
  `setXIncludeAware` is never called: Android's factory throws for every call of it, and XInclude is off by
  default. Any parser failure → no result.
- Per type, the first entry with implicit TLS wins; a STARTTLS entry only when no implicit-TLS entry passes
  (RFC 8314 §5.1). Example (esiliati.org, a live-test account): IMAP 143 STARTTLS is listed before 993 SSL →
  `imap.esiliati.org:993`; SMTP `smtp.<d>:587` STARTTLS before `mail.<d>:465` SSL → `mail.esiliati.org:465`.
- An entry passes when: `socketType` is `SSL` (implicit), `STARTTLS`, or `TLS` (by port: 143 and 587 STARTTLS,
  else implicit), never `plain`; the port is 1..65535; the host passes `normalizeHost` and has no
  `%EMAILADDRESS%` or `%EMAILLOCALPART%` (the local part would go to DNS); an authentication is
  `password-cleartext`, `plain`, `password-encrypted` or `secure` (`OAuth2` only for Google and Microsoft
  hosts). POP3 entries and unknown elements are ignored.
- `%EMAILADDRESS%`, `%EMAILLOCALPART%`, `%EMAILDOMAIN%` are replaced in user names and host names. A user name
  equal to the address → `login = null`.
- Fetch: `RemoteFetcher.fetch` (§11.2) with HTTPS at every hop, at most 3 redirects, each inside the site of the
  domain or to a host of the table, 64 KiB, 8 s, status 200 only, no cookies, no address in the URL.

**DNS.** `DnsWire` builds one query (random id, recursion desired, one IN question) and reads the answer: every
read is bounds-checked; the QR bit is required; TC set → no result; RCODE ≠ 0 → no records; names with labels
≤ 63 and names ≤ 255 bytes, compression pointers only `0xC0`, at most 16 jumps; other record types (CNAME …) are
skipped; names in lower case without the trailing dot. A malformed answer gives no result, never an exception.
`SystemDns` sends the query with `DnsResolver.rawQuery` (the `byte[]` overload) from IO: it follows Private DNS
and a VPN like every other query of the phone. 5 s timeout, a cancel signal, and the answer id must be the
question id. No other resolver is used.

**Hosts.** Every host from autoconfig, SRV, MX and the server page passes `Discovery.normalizeHost`: IDN to
ASCII (`xn--`), lower case; null for an IP address, a single label, `localhost` and names under `.local`,
`.lan`, `.internal` and `.home.arpa`. Hosts are shown in ASCII form before the password field, and the TLS
certificate is checked against the stored host.

**User-name retry** (RFC 6186 §4): only in setup, only for servers that SRV found, provider `OTHER`, no user name:
when the first password test fails with `AuthRequired`, the test runs once more with the local part as the user
name, and a success stores it. At most once per setup session, never after the user changed the password or the
servers (fail2ban and lockouts count failures).

### 6.4 Credentials
```kotlin
interface Credentials {
    suspend fun authImap(client: ImapClient)
    suspend fun authSmtp(client: SmtpClient)
    val expiresAt: Long?          // elapsedRealtime when the IMAP session must be renewed (OAuth/device)
    suspend fun invalidate()
    suspend fun revoke() {}       // account removal
}
```
`credentialsFor(app, account)` caches one instance per account; a change of provider, address, user name,
sign-in method or device account gives a new one. Secrets are read from the Vault for each use.
- `PASSWORD` → `PasswordCredentials(provider, account.login, secret)`: `authenticatePassword` / `authPassword`
  (§4.4, §4.7). Gmail app passwords: spaces removed.
- `OAUTH` → the issuer of the provider (Gmail → Google, Microsoft → Microsoft) and `OAuthCredentials`. A provider
  without an issuer gets credentials that refuse every login ("Plico cannot use this sign-in method with this
  server"): a token never goes to another server.
- `DEVICE` → `DeviceCredentials`, Gmail only (else refused as above).

`OAuthCredentials(user, refreshToken, endpoint, …, onRotated, hosts, smtpOff)`:
- The refresh token stays in the Vault, the access token in memory. Before every IMAP login the token must have
  ≥ 45 min left (SMTP: ≥ 5 min), else refresh (`push PB17`). `expiresAt` = token expiry − 5 min.
- Host check first: `client.host` must be in the issuer's `hosts` (§6.5), else `MailError.Setup` before a token is
  read or refreshed (an empty set refuses every server). `MailErrors.localized` gives the text.
- A refresh token that the issuer rotates (Microsoft) is written through `onRotated` before the new access token
  is used: in one transaction, and only when the row still signs in with OAuth and still holds the old token, so
  that a "Sign in again" or another method during the refresh wins.
- `invalid_grant` → `AuthRequired`.
- Microsoft (`smtpOff` set): an SMTP refusal of the token drops it, refreshes once and tries once more; a second
  refusal, or `5.7.139`/`5.7.30` (SMTP AUTH off), gives `MailError.Setup` with "Microsoft does not let apps send
  mail from this account …". Only that send fails (Setup is permanent for the draft); IMAP goes on. Gmail is
  unchanged.
- Account removal: Google → `POST https://oauth2.googleapis.com/revoke` with the token in the body. Microsoft
  has no revoke endpoint for public clients: no request; the remove dialog names account.live.com/consent/Manage
  (personal account) and myapps.microsoft.com (work or school account).

`DeviceCredentials`: `getAuthToken(account, "oauth2:https://mail.google.com/", null, false, …)`, only for a
server in Google's token hosts (checked before a token is fetched). The acquired time is kept in memory;
unknown or older than 45 min → `invalidateAuthToken` + get again (at most once per 10 min). `expiresAt` =
acquired + 55 min. `KEY_INTENT` → `AuthRequired`. `UNREGISTERED_ON_API_CONSOLE` → setup message (client not
registered).

An "expired" `* BYE` (the session or the token is over) is a normal reconnect on Gmail accounts and on every OAuth
or device sign-in (no backoff step; the token is invalidated; §7.1).

### 6.5 Browser OAuth (`BrowserOAuth`, `OAuthIssuer`)
One engine for both issuers, with the user's own client ID.

| | Google | Microsoft |
|---|---|---|
| Authorization | `https://accounts.google.com/o/oauth2/v2/auth` | `https://login.microsoftonline.com/common/oauth2/v2.0/authorize` |
| Token | `https://oauth2.googleapis.com/token` | `https://login.microsoftonline.com/common/oauth2/v2.0/token` |
| Scopes | `https://mail.google.com/ openid email` | `openid email profile offline_access https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send`; a refresh sends the two mail scopes + `offline_access` |
| Extra parameters | `access_type=offline`, `prompt=consent` | `response_mode=query`, `prompt=select_account`; scope also in the code exchange |
| Redirect URI | `${applicationId}:/oauth2redirect` | `${applicationId}://oauth2redirect` |
| Revoke | `https://oauth2.googleapis.com/revoke` | none |
| Token hosts (`hosts`) | `imap.gmail.com`, `smtp.gmail.com` | `outlook.office365.com`, `smtp-mail.outlook.com`, `smtp.office365.com` |
| Client ID | Settings → Sign-in clients → Google client ID | Settings → Sign-in clients → Microsoft client ID (a GUID, stored in lower case) |

- Both: PKCE S256, `state`, `login_hint` = the typed address, the default browser through `ACTION_VIEW` (no
  WebView, no Custom Tabs). Both send a `prompt`, so that no grant is ever silent. Microsoft always uses the
  authority `common` (no tenant per account).
- Before the browser opens: the pending entry `{issuer, state, verifier, email, createdAt}` goes to Prefs (§5).
  **Redirect hijack check**: the manifest declares `<queries>` for `VIEW` with the app's scheme, in two forms (the
  scheme only for the Google path form, and any host for the Microsoft host form); `start()` lists
  the activities for the redirect URI, and when another package can receive it, setup stops with "Another app on
  this phone can receive the sign-in answer for Plico" (PKCE already stops another app from using the code; this
  stops a chooser that blocks the sign-in).
- `OAuthRedirectActivity` (exported, VIEW + BROWSABLE, the scheme) passes the URI to `BrowserOAuth.onRedirect`. It
  accepts only the redirect form of the pending issuer (Google: no authority, path `/oauth2redirect`; Microsoft:
  authority `oauth2redirect`, empty path or `/`), the right `state`, age ≤ 10 min, and one use; a `#` fragment is
  ignored. It stores the code or the error (with the first `AADSTS<n>` code of `error_description`) and starts
  `MainActivity` (singleTop); setup exchanges the code.
- Mail grant check: the granted scopes must contain the mail scope (Microsoft: `IMAP.AccessAsUser.All`,
  case-insensitive).
- Identity: Google: the `email` claim of the id_token (required). Microsoft: the `email` claim, else the typed
  address (work accounts can have no `email` claim); the login (the XOAUTH2 user, `Servers.login`) is
  `preferred_username` when it is an address that differs from the email. The login never becomes a From address
  (a UPN such as `jdoe@contoso.onmicrosoft.com` is often not a send-as address). No signature check (direct TLS
  response). The UI shows "Signed in as …".
- A browser or device result always stores the built-in servers of the issuer (never servers from the setup
  state).
- Wrong account (when adding): a result whose `email` claim, or the selected device account, is not the same
  mailbox as the typed address (case; with gmail.com and googlemail.com also dots in the local part) → "You signed
  in as … Sign in as …". Microsoft without an `email` claim uses the typed address, so the check passes.
- Wrong account (sign in again): the address must be the account's own. Microsoft without an `email` claim
  (`SignIn.fromHint`): the address is the login hint, which is always the account's own, so the login decides: a
  login that is not the stored XOAUTH2 user (`Account.login`) → "You signed in as <login> …"
  (`SetupState.wrongBrowserAccount`). Else another account picked in Microsoft's account chooser would sync its
  mailbox into the rows of this account. Without a login the XOAUTH2 user stays the account's address, so the
  server decides. With an `email` claim the address check is enough (a changed login of that account is kept).
- Errors: the AADSTS code first. `AADSTS50194`, `50020`, `700016` → the account-types text (client registered for
  one tenant, an account of another tenant, or a wrong client ID); `AADSTS90094`, `65001` or `consent_required` →
  "An administrator must approve Plico"; `AADSTS7000218`, `50011` or `invalid_client`/`unauthorized_client` →
  the client text of the issuer (Microsoft: "make sure that the redirect URI … is under Mobile and desktop
  applications"); `invalid_grant`, `interaction_required` → `AuthRequired`; `access_denied` → "You did not give
  access". A redirect error shows the AADSTS code.
- "Sign in again" overwrites the stored token; it never revokes after the new consent (`privacy PS16`).

**Own client IDs.** Google ties an Android OAuth client to the package name and the signing SHA-1, and each pair
can exist in only one Cloud project (`privacy PS9/PS10`). Microsoft lets an app open a mailbox only through a
registered client. So Plico has no client of its own: both OAuth sign-ins show only after the user enters their
own client ID (on setup's own-client page or in Settings → Sign-in clients). Debug builds can preset both from
`local.properties`; release builds never contain one.
- Google guide: create a project, consent screen "External / In production", a non-personal support email, an
  Android client for `app.plico` + the release SHA-1. The README tells the user to sign a public GitHub APK with
  a different key or applicationId than their personal build; public-APK users have the app-password method.
  Google may refuse custom schemes for new clients (`gmail G9`); the setup text says so and points to the app
  password.
- Microsoft guide: in the Microsoft Entra admin center, App registrations → New registration, account types "Any
  Entra ID tenant and personal Microsoft accounts", platform "Mobile and desktop applications" with the redirect
  URI `app.plico://oauth2redirect` (debug: `app.plico.debug://oauth2redirect`), then copy the Application
  (client) ID. A work or school account can need the approval of its administrator. Outlook.com needs "IMAP for
  devices and apps" on (Settings → Mail → Forwarding and IMAP).

Device account (`DeviceGoogleAuth`): shown only when a `com.google` authenticator exists and a Google client ID
is set. Setup launches `AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null,
null, null)`, stores the name, then calls the Activity overload of `getAuthToken` once so the consent screen
shows during setup (`simplicity EM21`, `apis AF5`).

`AuthRequired` → `account.state = 1`, one "Sign-in needed" alert, no retries until the user acts.

### 6.6 Connection test (`AccountProbe`)
`probe(app, provider, servers, credentials): ProbeResult(notes, idle)`; setup runs it before it saves an account.
- IMAP: `ImapClient.connect(servers.imap)`, `authImap`, `requireCapabilities()`. A Gmail server (`X-GM-EXT-1`)
  for another provider → `Setup` ("This server is a Gmail server"). NetEase hosts get the minimal `ID`. Gmail: the
  LIST checks (All Mail hidden from IMAP stops setup; Trash or Spam hidden adds a note). Every other provider:
  `LIST` **and** `EXAMINE INBOX`, because Exchange ("User is authenticated but not connected": IMAP off, or an
  alias was used) and NetEase ("Unsafe Login") refuse only mailbox commands. `idle = "IDLE" in caps`; without IDLE
  a note says that Plico checks for new mail every 15 minutes.
- SMTP: `SmtpClient.connect(servers.smtp)`, `authSmtp`, `QUIT`. Microsoft: a `Setup` from SMTP (SMTP AUTH off)
  adds the note "Plico can receive mail, but it cannot send it" instead of a failure.
- Microsoft: IMAP refuses the token that the browser sign-in has just given (Exchange `NO AUTHENTICATE failed.`,
  an `AuthRequired` with an `ImapNoException` cause) → `Setup` with the IMAP text ("Microsoft did not open the
  mailbox. IMAP must be on for this account … the administrator must turn it on … the main address"), the same
  text as "not connected". The token is new, so a sign-in again cannot help.
- A failure is a `ProbeException(endpoint, error)`: the endpoint that failed and the classified `MailError`
  with its cause chain. `ImapMissingCapabilityException(AUTH=PLAIN)` and `SmtpAuthUnavailableException` →
  "The server does not accept a password sign-in that Plico can use"; everything else → `MailErrors.classify`
  + `localized`. Setup maps it to a page text (§13).

## 7. Sync (`sync`)

### 7.1 `AccountSession`
One per account. It owns at most one `ImapClient` and one worker coroutine (the **loop**) on
`Dispatchers.IO`. All IMAP work of the account runs on the loop, in order.

```kotlin
class AccountSession(...) {
    var mode: Mode                                        // IDLE, POLL or OFF; set by the push engine
    val status: StateFlow<Status>                         // Offline, Connecting, Syncing, Idle, Waiting, Problem, NoNetwork
    fun kick(reason: Reason = Reason.WORK)                // never writes to a socket on the caller's thread
    fun onWake(now: Long)                                 // engine wake (§8.3): synchronous, no I/O
    fun onNetworkEvent(event: NetworkRules.Event, network: NetState?)
    suspend fun <T> request(closeOnCancel: Boolean = false, block: suspend SyncContext.() -> T): T   // UI work: queued, run by the loop, awaited
    fun nextDueAt(): Long?                                // earliest of keepalive, OAuth expiry, backoff, poll, op run_at, send_at
    fun signedIn()                                        // the user fixed the sign-in
    fun close()
}
```
- `kick()` sets `workPending = true`, sends to a conflated channel, and launches `client.stopIdle()`
  on `Dispatchers.IO`. The loop clears the flag before it starts work, checks it again just before
  it sends IDLE, and `IdleHooks.stopRequested()` returns it (`push PB1`).
- Loop: connect (`account.servers.imap`) → authenticate → `requireCapabilities` → Gmail-server guard →
  NetEase `ID` → COMPRESS → `ENABLE CONDSTORE` → work → IDLE (instant mode and the server has IDLE) or LOGOUT
  (poll mode; or after 2 min without work when the account is not instant at that moment). "Work" = the steps
  below in this order:
  1. **Fast path** for the IDLE mailbox (§7.2 step 4 / §7.3 fast path) → commit → **notify** (§9).
  2. Due ops (§7.4), then the new mail of the targets of moves without COPYUID (§7.4), then due sends (§7.5).
  3. Queued `request {}` blocks (UI: open folder, load body, attachment, search).
  4. Flag/expunge sync of the IDLE mailbox, the notify-folder sweep, other changed folders.
  5. Bodies in the window for new rows, then one bounded backfill step (only on unmetered network or
     while the app is visible).
  6. **UIDNEXT check** before IDLE (`push PB2`, `protocol PS3`): SELECT the IDLE mailbox if needed; if
     its UIDNEXT is higher than expected (other servers: `folder.uidnext`; Gmail: last INBOX UIDNEXT + new
     inbox messages of this cycle), run the fast path again.
- IDLE `Changed` → debounce 1 s under the wake lock (batch bursts) → kick. `Keepalive` → report to
  `KeepalivePolicy`.
- Connect details:
  - **Gmail-server guard:** an account that is not Gmail and whose server advertises `X-GM-EXT-1` fails with
    `Setup` ("This server is a Gmail server. Remove this account and add it again."). The generic MOVE and
    expunge rules must never run on Gmail's All Mail.
  - **NetEase:** for the table hosts with `needsId` (163.com, 126.com, yeah.net) the session sends
    `ID ("name" "Mail")` after login: no version, no OS, no device data. No other server gets `ID`.
  - `ENABLE CONDSTORE` only when the server has both CONDSTORE and ENABLE.
  - **IDLE capability:** `idle = "IDLE" in caps`. When it differs from `account.idle`, the session stores it
    (`AccountDao.setIdle`) and the push engine applies the modes again: an account without IDLE polls (§8.3).
  - The keepalive scope is checked at every connect (§8.3). After a server change it differs: the session makes a
    new `KeepalivePolicy` and lists the folders again.
  - A working connection clears a sign-in or TLS problem, and for accounts that are not Gmail also a setup
    problem (it comes from the connection or from a mailbox command, and comes back when it stays). Gmail setup
    states are decided by the folder list (§7.3).
- **Session expiry:** a `* BYE` with an "expired" text after at least 1 min of connection is a quiet reconnect
  (invalidate the token, no backoff step) on Gmail accounts and on every OAuth or device sign-in (Microsoft
  too).
- Errors go through `MailErrors.classify` and `MailErrors.localized` (§14).

### 7.2 Mailbox sync (every kind-0 mailbox)
1. Folder list: on first connect, then at most every 6 h, when an unknown label appears, and after a server
   change or a setup problem. LIST with SPECIAL-USE (`RETURN (SPECIAL-USE)` only with LIST-EXTENDED, §4.4).
   `\Noselect` (and `\NonExistent`) mailboxes are not stored. Gmail: §7.3.
   - **Riseup** (`FolderRules.mailboxes(…, riseup = true)`, unchanged): roles from attributes (`\Sent \Drafts
     \Trash \Junk \Archive \All`); INBOX by name. Archive (`protocol PS5`, `simplicity EM5`):
     `account.archive_folder`, else the `\Archive` mailbox, else a mailbox named `Archive` or `Archives`
     (case-insensitive), else CREATE `Archive` on the first archive action. Missing Sent/Drafts/Trash: CREATE on
     first use. Missing `Spam`: CREATE exactly `Spam` on the first spam action (riseup R12, R20). Never rename or
     delete `Spam`.
   - **Other servers** (Microsoft and every other server), in this order:
     1. Other people's mailboxes: a mailbox under a non-empty other-users or shared NAMESPACE prefix is stored
        hidden, without a role (not synced). The longest matching prefix decides, a personal prefix wins, and an
        empty shared prefix (Cyrus `(("" "."))`) is ignored. Without NAMESPACE nothing is hidden this way.
     2. INBOX by name; the user's archive folder; SPECIAL-USE attributes (one mailbox per role). `\All` and
        `\Flagged` mailboxes are stored hidden (Dovecot virtual folders would show every message twice).
     3. Role names, case-insensitive, on the leaf of the **direct children of the personal root** only
        (`FolderRules.ROLE_NAMES`: English names with those of Exchange, and German, Italian, French, Spanish,
        Dutch, Swedish, Polish, Czech and Russian names, e.g. "Sent Items", "Deleted Items", "Junk E-mail", "Posta inviata",
        "Odeslaná pošta"). SENT, DRAFTS and ARCHIVE: the first name of the list that a mailbox has. TRASH and
        JUNK: only when exactly one direct child matches any name of the role (a wrong Trash would be emptied,
        and Trash has no mass-delete guard). No `[Gmail]/` strip: Gmail never reaches this code, and imported
        Gmail folders on another server get no role.
   - **Personal root** (`FolderRules.prefixOf`, the same at LIST time and at op time): the personal NAMESPACE
     prefix when it is not empty; else `INBOX<delimiter>` when every mailbox other than INBOX (at least one) is
     below it (Courier and cPanel servers without NAMESPACE or with `""`); else `""`. Riseup accounts and Gmail
     accounts always have `""`.
   - Archive of other servers: `account.archive_folder`, else the mailbox with the ARCHIVE role, else CREATE
     `<root>Archive` on the first archive action. Missing Sent, Drafts, Trash and Spam: CREATE `<root>Sent`,
     `<root>Drafts`, `<root>Trash`, `<root>Spam` on first use (§7.4).
2. Cheap check: STATUS (or LIST-STATUS when advertised). Skip the mailbox only when UIDNEXT, MESSAGES and
   UIDVALIDITY are unchanged and the flags are unchanged too: a known HIGHESTMODSEQ that is the same
   (`protocol PS10`), or, on a server without CONDSTORE (Exchange), only while no flag check is due (user
   refresh, folder open, or a sweep 6 h or more after the last flag check). The 6 h count from the last sweep
   that checked the flags, not from the last sweep: in poll mode every poll is a sweep. Else every 15-min poll
   would fetch the flags of every message of every folder, or, with the 6 h counted from the last sweep, a polling
   account would never check them. The servers of Riseup and of Gmail have CONDSTORE: no change on them.
3. SELECT. UIDVALIDITY changed → drop pending ops that use this folder (refetch-undo, §7.4), then
   delete the local rows — through the guard (step 6) when more than 50 rows.
4. **New mail**: skip when server UIDNEXT ≤ `folder.uidnext`. First sync (`backfill_uid = 0`):
   `FETCH <max(1, EXISTS−199)>:* (UID …)`, set `backfill_uid` to the lowest UID fetched (`simplicity EM3`).
   Later: `UID FETCH <folder.uidnext>:*`; ignore returned UIDs below the start. Items: `UID FLAGS
   INTERNALDATE RFC822.SIZE MODSEQ BODY.PEEK[HEADER.FIELDS (DATE FROM TO CC BCC REPLY-TO SUBJECT
   MESSAGE-ID IN-REPLY-TO REFERENCES CONTENT-TYPE LIST-UNSUBSCRIBE LIST-UNSUBSCRIBE-POST
   AUTHENTICATION-RESULTS)]` (+ `X-GM-MSGID X-GM-LABELS` on Gmail). Then `folder.uidnext = UIDNEXT`.
   A server whose SELECT has no UIDNEXT gives an estimate (§4.4); the stored `folder.uidnext` is then never below
   the STATUS UIDNEXT of the same sweep (also in the notify sweep, §8.3), else step 2 would never skip the folder.
5. **Flags**: `UID FETCH 1:* (UID FLAGS [X-GM-LABELS]) (CHANGEDSINCE <modseq>)`, update existing rows
   only. NOMODSEQ, no CONDSTORE, or HIGHESTMODSEQ went backwards → `UID FETCH <newest 200 local> (UID
   FLAGS)` on push events, the full local range when the folder is opened or every 6 h.
6. **Expunge** (`protocol PS4`): skip when `backfill_uid = 1` and SELECT EXISTS = local row count of
   the mailbox (hidden rows included). Otherwise compare `UID SEARCH RETURN (COUNT) UID <a>:<b>` per
   window of 5,000 local rows; for windows that differ, `RETURN (ALL)` and delete the missing rows. Without
   ESEARCH both forms return the full list, so one plain `UID SEARCH UID <a>:<b>` per window is compared by its
   size.
   **Guard** (`protocol PS9`, `simplicity EM12`): not for `trash`/`junk`; if one pass would delete
   > 50 % of a mailbox's rows and > 50 rows, set `folder.guard = 1` and stop syncing that folder (ops
   on it wait). The folder shows a banner "Many messages are gone on the server · Resync". Resync drops
   the rows, resets uidvalidity/uidnext/modseq/backfill and clears the guard.
7. **Bodies** for rows in the window (`date ≥ now − body_days`, or all when `body_days = 0`):
   `BODYSTRUCTURE`, then the first 50 display text parts (`BODY.PEEK[<section>]`, each ≤ 2 MB; larger
   and later text parts are shown as an attachment), batches of 25. The items go in FETCH lines of about
   4 KB, below the line limit of the server (Dovecot: 64 KiB). Inline images and attachments on demand.
   A batch that gets NO or BAD is tried again one message at a time on the same connection; a batch that
   the reader refuses (a line > 1 MiB) closes the connection, and the next one tries it one message at a
   time. A message that fails alone is skipped until the process restarts (`BodySkips`), so that it cannot
   stop the cycle before IDLE. A NO with a busy or throttle code backs off as before. Opening the message
   still loads its body.
8. **Backfill** (one step per cycle): `UID FETCH <max(1, backfill_uid−500)>:<backfill_uid−1>` headers
   (sequence numbers are not used, because UIDs are sparse; when the range returns nothing, continue
   with `UID SEARCH RETURN (MAX) UID 1:<backfill_uid−1>`). Without ESEARCH: plain searches in windows of
   50,000 UIDs downward, up to the first window with a hit (each `* SEARCH` line stays far below the 1 MiB line
   limit of the reader). Set `backfill_uid = 1` when done.
- Sync never changes `seen flagged answered draft hidden` or label rows of a message that a pending op
  references (`protocol PS2`).
- `Changed` events from IDLE run steps 4, 5 (CHANGEDSINCE) and 6 for the IDLE mailbox only.
- `\Deleted` (every server except Gmail): a message with the server flag `\Deleted` is stored with
  `message.deleted = 1` (new rows and flag updates) and never shown (§5). Other clients keep struck-through
  mail, and a skipped guarded expunge (§7.4) leaves such messages on the server; before, they showed as normal
  mail.

### 7.3 Gmail
- Mailboxes (kind 0): `\All` (synced), `\Junk`, `\Trash` (synced), `\Drafts` (stored with `hidden = 1`,
  for APPEND and draft deletion), found by SPECIAL-USE, never by name.
- **Missing mailboxes** (`protocol PS13`): no `\All` → `account.state = 3` "Turn on Show in IMAP for All
  Mail (Gmail settings → Labels)", no sync. No `\Trash` or `\Junk` → same hint, Delete/Spam off for
  that account (`FolderDao.abilities`), sync continues. EXISTS of All Mail exactly 1000/2000/5000/10000 → one
  note about Gmail's IMAP folder size limit.
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
`append` (drafts and the Sent copy, from a file in `outbox/`), `delete_draft` (by Message-ID).
- `run_at` = now + 6 s for undoable actions (archive, delete, move, spam) so the snackbar can undo
  (undo deletes the op and restores the row). The snackbar closes 1 s before `run_at`; an undo that comes
  after the op started says "Too late to undo". Notification actions and everything else: `run_at = now`.
- The loop runs due ops in id order. `nextDueAt()` includes the earliest `run_at`, so the alarm wakes
  the session (`push PB7`, `protocol PS12`, `simplicity EM1`).
- The runner resolves UIDs from the rows at run time. It SELECTs the folder first; a UIDVALIDITY
  mismatch drops the op (`protocol PS11`).
- After MOVE, apply COPYUID: update `folder_id`/`uid` of the row in place and keep body/attachments
  (`protocol PS26`); without COPYUID delete the row. On servers other than Gmail the Message-IDs of those rows
  go into the session's `movedIn` map (by target path, cleared at connect). Right after the ops, the session
  fetches the new mail of those targets on the same connection (also in poll mode), and the rows with those
  Message-IDs are stored with `notified = 1`: "Not spam" or a restore into INBOX never notifies.
- A server without MOVE gets the COPY fallback (§4.4). Expunge: deletes outside Trash and Spam use the guarded
  expunge (`uidExpunge(guard = true)`): on a server without UIDPLUS it never removes a `\Deleted` message of
  another client; when it skips the EXPUNGE, the messages stay `\Deleted` on the server and their rows are not
  shown (§7.2). Their UIDs go into `folder.own_deleted` (§5, Migration 2 → 3) and are passed as `allowed` to every
  later guarded expunge of that mailbox: else Plico's own leftovers would look like another client's mail and stop
  every later expunge there, also after the other client's message is gone. A plain EXPUNGE of the mailbox clears
  the record, and so does a resync (UIDVALIDITY change). Empty Trash/Spam (every message up to a UID) and
  permanent deletes inside Trash or Spam use a plain `EXPUNGE` without UIDPLUS. Servers with UIDPLUS (Gmail,
  Riseup) never use the guard or the record, so their paths do not change.
- **Default names**: an action queues only the bare name (`Trash`, `Spam`, `Archive`, offline too); Sent and
  Drafts uploads use `Sent` and `Drafts`. At run time a target with a role and no folder row with that path gets
  the personal root (§7.2): `INBOX.Trash` on a Courier server. "New folder" (also in the move picker) gets the
  same prefix and the namespace delimiter. The row of a created mailbox uses the delimiter of the personal
  namespace, else that of the stored mailboxes. Riseup accounts and Gmail accounts have the root `""`: no
  change.
- Errors (`protocol PS14`): transient = IOException, `BYE`, `[UNAVAILABLE] [INUSE] [SERVERBUG]
  [THROTTLED]`; backoff 5 s, 30 s, 2 m, 10 m, 1 h; give up after 24 h of backoff (time without a try does not count). Every other NO/BAD is permanent.
  `[TRYCREATE]` on move/append → CREATE the target once and retry; also a NO without a response code when no
  folder row of the account has that path (TRYCREATE is only a SHOULD). A dropped op is undone by
  **refetching the truth**: `UID FETCH <uids> (FLAGS [X-GM-LABELS])` for its rows (missing → delete the
  row), `hidden = 0`, and a snackbar/banner with the server text.
- Before APPEND, check the size against APPENDLIMIT and `traits.maxMessageBytes` (§6.1).

### 7.5 Sending (`Sender`) — the draft table is the only outbox
- Send: `state = 1`, `send_at = now + undo seconds` (Prefs, default 5; 0 = off); the Undo snackbar closes 1 s
  before `send_at`, and an Undo after the send started says "Too late to undo". Due sends: `state = 2`
  → build MIME (§10) → write `outbox/<id>/message.eml` → SMTP. On the final 250, in one transaction:
  `state = 4`, queue `append` (the Sent copy with Bcc, `\Seen`, see below), `delete_draft` (if `on_server`),
  `flags +\Answered` (source message), update contacts (`protocol PS1`). When no op references the draft
  any more, delete the row and its outbox dir.
- Size: the message must fit `traits.maxMessageBytes` (§6.1), lowered by the live SMTP `SIZE`.
- **Sent copy:**
  - Gmail and Microsoft: never (the server saves SMTP-submitted mail in Sent).
  - Riseup: the APPEND runs at once, without a check (unchanged).
  - Other servers: never for a table server that saves the copy itself (`serverSavesSent`: Yahoo, AOL). Else the
    `append` op gets `run_at = now + 60 s`, because an unknown server may save its own copy. Before the APPEND,
    the op fetches `BODY.PEEK[HEADER.FIELDS (MESSAGE-ID)]` of the newest 50 messages of Sent
    (`UID FETCH <max(1, UIDNEXT − 50)>:*`); an exact match of the Message-ID skips the APPEND (the server saved
    a copy; this also makes a retried APPEND safe). A FETCH, not `SEARCH HEADER`: a search index can be late. A
    refused SELECT (the mailbox is gone) counts as no copy, and the APPEND creates `<root>Sent`.
- Errors: `SmtpRecipientException` → `state = 3` with the rejected address(es). 5xx → `state = 3` with the
  server text. Transient (connect, 4xx at MAIL/RCPT/DATA except RCPT 4.1.x, IOException before the final
  dot) → backoff, max 6 attempts or 24 h, then `state = 3`. `SmtpUncertainException` → `state = 5` "May have been sent — check
  Sent before you retry". At process start (`Sender.onProcessStart`, before the first send) every `state = 2`
  row becomes `state = 5` with that text, and `state = 4` rows that no op needs any more are deleted.
- Drafts view shows failed drafts (states 3 and 5) with Edit and Retry; Retry of a state-5 draft asks first.
  A failed send shows a mailbox banner "N messages not sent · View" and, while the app is not on screen, one
  `alerts` notification "Message not sent" per account (no content). At process start, outbox dirs without
  a draft row are deleted.

### 7.6 Drafts (`Drafts`)
- Every draft has one `message_id` from its first save; all revisions and the final message use it.
- Compose saves the row locally on every change pause (debounced) and on `onStop`. Leaving compose
  queues `append` to the drafts mailbox with `(\Draft \Seen)` (Bcc included) and then `delete_draft`
  for older revisions (`UID SEARCH HEADER Message-ID "<id>"` in the drafts mailbox, all UIDs except the
  new APPENDUID). Other servers (Riseup too) delete with `\Deleted` + expunge in Drafts (guarded without
  UIDPLUS, §7.4). Gmail: `UID MOVE` to Trash, then `\Deleted` + `UID EXPUNGE` of the COPYUID in Trash
  (`protocol PS7`).
- Without APPENDUID (Microsoft and other servers): a fresh SELECT of Drafts right before the APPEND gives
  UIDNEXT, and the new revision gets a UID at or above it. `delete_draft` keeps that value: it deletes the UIDs
  below it that `UID SEARCH HEADER Message-ID` finds, plus the local Drafts rows with that Message-ID (for a
  search index that is late). The SELECT is fresh every time: a mailbox that stays selected keeps its old
  UIDNEXT, and a second upload would then keep the first revision. A refused SELECT (the mailbox is gone): the
  APPEND creates it and no revision is deleted.
- A draft created by a share intent is local until the user edits it.
- Opening a server draft (a row in the Drafts view) copies it into a local draft with its
  `message_id`, text and attachments (downloaded into `outbox/`).

### 7.7 Search
Local first: FTS4 over subject, people, body. "Search on server": Gmail `X-GM-RAW` in All Mail;
other servers `UID SEARCH TEXT` in the open folder only (INBOX in the unified view). Without ESEARCH the server
search goes in windows of 50,000 UIDs from UIDNEXT down (`UID SEARCH UID a:b TEXT …`; the first window ends at
`*`, so that mail after an older SELECT is found), until 200 hits are not stored yet or the windows reach UID 1.
`CHARSET UTF-8` for non-ASCII. Hits not yet local are fetched, newest first, at most 200 per search (headers,
and the bodies of 25 for the local index); they do not notify, except new mail at or above the folder's UIDNEXT
(§9 rule). The list shows the local hits plus the server hits (at most 1,000). The UI shows "Searching on
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
  `push PB14`), poll. Default: instant. `PushModes.effective(mode, idle, unmeteredWifi, charging)`: an account
  whose server has no IDLE (`account.idle = 0`, §7.1) always polls; its `push_mode` is kept for a later server
  change. IDLE is the one extension that cannot be emulated.
- **Scheduler and alarms** (`push PB3`, `apis AF2`): one `Scheduler` owns one `PendingIntent.getBroadcast(
  …, Intent(ctx, AlarmReceiver::class.java), FLAG_IMMUTABLE)`. `reschedule()` takes the minimum of all
  sessions' `nextDueAt()` (plus a 30-min backstop while there is no network) and arms it with
  `setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` when `canScheduleExactAlarms()` or the battery
  exemption holds, else `setAndAllowWhileIdle`; `SecurityException` → inexact. `AlarmReceiver.onReceive`
  (exported=false): acquire the `alarm` wake lock (10 s; the engine releases it after the sessions got the wake
  and the next alarm is armed; the timeout covers a service start), skip if paused, call
  `engine.onWake(ALARM)` if the engine runs, else `startForegroundService` in try/catch; `reschedule()` in
  `finally`. `reschedule()` arms on the caller's thread, so the alarm is set before the caller's wake lock
  ends. `AccountSession.onWake(now)` runs on the engine's thread and does no I/O (it sets flags, renews the
  session lock and kicks), so a session holds its own lock before the `alarm` lock ends.
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
  - Client mode (`push PB11`): refresh due = `idleStart + interval`. Start 9 min (Gmail), 15 min (other servers:
    `traits.idleStartMinutes`). After 3 refreshes in a row that found the socket alive (not early), +2 min (max
    28). Failure = a refresh that found the socket dead, or a server BYE/close at IDLE age ≥ 5 min without a
    network change (then also cap the interval at age − 1 min). Microsoft and other servers: a close counts only
    when it has no BYE (EOF, reset) or a BYE whose text names a timeout (`idle|inactiv|autologout|timeout`); a BYE
    at server shutdown must not lock a short interval for 24 h. Riseup accounts and Gmail accounts: every end that
    the app did not cause (unchanged). A failure counts only when the next IDLE runs on the same `Network` with no
    network event between (a socket that died with its network is no failure). On failure: −2 min (min 5) and
    **lock**. A locked interval never grows; each later failure lowers it again. Unlock on a new network key or
    after 24 h.
  - Refresh = DONE → tagged OK (20 s) → IDLE → `+`. Timeout → close → reconnect.
  - Scope of the learned intervals (`KeepalivePolicy.scopeOf`): on Riseup accounts and Gmail accounts the provider
    id (their learned values stay), on every other account the IMAP host in lower case (never the address;
    `=@;|` and spaces are removed), so that two servers never share one value. A server change gives a new scope
    (§7.1).
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
  `CertificateException`/`CertPathValidatorException`, `SSLPeerUnverifiedException`, hostname check); every other
  `SSLException` is `Network`. `StartTlsException` (STARTTLS refused or broken) is `Tls` like a certificate
  failure; `TlsHandshakeException` (no TLS on the port) is `Network`. `Tls` uses the alarm backoff (15, 30, 60
  min) plus a retry on network change, shows the state in the app at once, and posts "Secure connection to <label>
  failed" only after 1 h or on a second network. A TLS failure while the network lacks VALIDATED is `Network`.
  Only `AuthRequired` stops retries.
- Keepalive cost (Dovecot example, e.g. Riseup): Dovecot's default 2-min keepalive is about 720 wakes/day per
  connection. Accounts on one phone share the phase only if the server sees the public client IP, otherwise
  each account has its own phase (`push PB18`). The account screen shows the measured interval and the
  estimated cost and offers the Wi-Fi-instant and poll modes.
- Notify-folder sweep (`push PB16`): at client refreshes, at Dovecot re-IDLE wakes (≥ 25 min) and after the
  fast path, `STATUS <folder> (UIDNEXT)` for each folder other than INBOX with `notify = 1` (Gmail: §7.3 All
  Mail STATUS). A change runs that folder's sync; the stored UIDNEXT is never below the STATUS value (§7.2
  step 4).

## 9. Notifications (`notify`)
- Channels: `service` (LOW), `mail_<accountId>` (DEFAULT; named after the account label; the user turns
  an account's notifications off here — the app skips posting when the channel importance is NONE),
  `alerts` (HIGH).
- **Rule** (`simplicity EM2`, `push PB21`): rows are inserted with `notified = 1`, except rows from the
  step-4 new-UID fetch of a folder whose `uidnext > 0` before this sync (not a first sync, not after a
  UIDVALIDITY reset). Such a row notifies when unseen, not `\Deleted`, `date ≥ now − 7 d`, in a notify scope
  (other servers: INBOX or `folder.notify = 1`; Gmail: `\Inbox` label or a label view with `notify = 1`) and
  the Gmail category filter passes. Then `notified = 1`.
- Posting order (`push PB8`, `apis AF7`): per account and batch, build the summary from the DB and post it
  **first**, with alert on (`setOnlyAlertOnce(false)`) when the batch has new mail. Then children for the 5
  newest new messages (`BigTextStyle`: sender, subject, snippet when known), `CATEGORY_EMAIL`, group
  `acct_<id>`, `GROUP_ALERT_SUMMARY`. Older new mail shows as "+N more" in the InboxStyle summary. Keep
  fewer than 40 children over all accounts (cancel the oldest). Content-only updates (snippet, cancels) use
  `setOnlyAlertOnce(true)`, batched at most once per second per account. Cancel the summary when no child is left.
- Privacy (`privacy PS11`): `VISIBILITY_PRIVATE` with a public version "N new messages" (+ the label only
  when the user set one); public versions never contain an address. A default label ("Gmail", "Outlook",
  "Riseup", "Mail", each with an optional number, §6.1) counts as not set. "Hide content" (or app lock on) →
  content-free everywhere. `setAllowSystemGeneratedContextualActions(false)`.
- Actions: Archive, Delete (`setAuthenticationRequired(true)`; only when the account can delete, §7.3), Mark
  read → `ActionReceiver` (exported=false; explicit intents; `FLAG_IMMUTABLE`) → `goAsync()` → local change +
  op (`run_at = now`) + kick; if the engine is not running, start `PushService` (allowed from a notification
  action).
- PendingIntents are unique per message: `Intent.setIdentifier("msg:<id>:<action>")` (`apis AF6`). Taps
  target the non-exported `activity-alias .OpenMessage` with data `plico:message/<id>` (`privacy PS15`);
  `MainActivity` is `singleTop`, handles `onNewIntent`, and reads internal routes only from the alias.
- A message that becomes seen, archived or deleted (local or sync) cancels its child and updates the summary.

## 10. Outgoing messages (`mime.MessageBuilder`)
```kotlin
class MessageBuilder(val from: Address, val to: List<Address>, val cc: List<Address>, val bcc: List<Address>,
                     val subject: String, val text: String, val messageId: String, val inReplyTo: String?,
                     val references: List<String>, val attachments: List<OutgoingAttachment>, val date: Long) {
    fun build(includeBcc: Boolean): ByteArray   // includeBcc = true only for Drafts and the Sent copy (§7.5)
}
```
- Headers in order: `Date` (UTC `+0000`), `From`, `To`, `Cc`, [`Bcc` only when `includeBcc`], `Subject`
  (RFC 2047 when non-ASCII), `Message-ID` (`<UUIDv4-UPPER@from-domain>`, created once per draft),
  `In-Reply-To`, `References` (first + last 10 ids), `MIME-Version: 1.0`, `Content-Type`,
  `Content-Transfer-Encoding`. All values pass `HeaderSafety.clean`; display names are quoted when they
  contain specials. Structured headers fold at commas/spaces before 78 chars; no line > 998 (`protocol PS23`).
- Never: `User-Agent`, `X-Mailer`, `Disposition-Notification-To`, `Content-Language`, IMAP `ID` (except the
  minimal `ID ("name" "Mail")` that NetEase servers need, §7.1). Read
  receipts are never sent or requested.
- Body: `text/plain; charset=utf-8`, `7bit` when ASCII with lines ≤ 998, else quoted-printable. Signature
  after `-- `. Reply attribution: "On <yyyy-MM-dd HH:mm> UTC, <name> wrote:".
- Attachments: `multipart/mixed`, boundary `----` + 30 base36 chars (`SecureRandom`), base64 at 76 chars,
  `Content-Disposition: attachment; filename*=utf-8''…` when non-ASCII, else a quoted filename with `"` and
  `\` escaped.
- Images (`privacy PS17`, `apis AF14`), from every source (pickers and share): JPEG/HEIC/HEIF → JPEG q≈90,
  longest side ≤ 2560 px (ImageDecoder applies orientation); PNG → PNG; static WebP → WebP lossless; GIF,
  animations and other image types unchanged with a "metadata kept" note. An image that cannot be re-encoded
  is not attached (it would keep its location data). Names with camera/screenshot date stamps become
  `image-N.<ext>`. "Original quality" per item.
- Videos are sent unchanged with a "metadata kept" note; a name with a date stamp becomes `video-N.<ext>`.
  Forward copies the attachments of the original unchanged: images and videos get the "metadata kept" note
  (`PLICO-PRIV-002`).

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
- Width: `useWideViewPort` and `loadWithOverviewMode` on, viewport `width=device-width` without
  `initial-scale`. A body that is wider than the screen is zoomed out until it fits: no side scroll.
- Dark mode: `setAlgorithmicDarkeningAllowed(darkTheme && prefs.darkEmail && !showOriginal)`. The activity
  theme is day/night: `values/themes.xml` parent `android:Theme.Material.Light.NoActionBar`,
  `values-night/themes.xml` parent `android:Theme.Material.NoActionBar`.

`RemoteFetcher` (`privacy PS12`): `HttpsURLConnection`, HTTPS only, no cookies, no Referer, no
Accept-Language, `User-Agent: Mozilla/5.0`, max 3 redirects handled manually. Every hop (and the
unsubscribe POST) rejects IP-literal hosts, `localhost`, single-label names and `.local .lan .home.arpa
.internal` (string check). Per request: 10 s connect/read, 20 s total, 5 MB. Per message: 20 MB, 4 parallel.
The app never declares `ACCESS_LOCAL_NETWORK`.

One GET engine serves remote content, sender logos (§12) and the autoconfig file (§6.3):
```kotlin
class Request(val url: String, val accept: String, val maxBytes: Int, val type: (String) -> Boolean,
              val maxRedirects: Int = 3, val hop: (URL) -> Boolean = { true }, val totalMs: Long = 20_000,
              val blockTrackers: Boolean = true)
sealed interface Fetched { class Ok(val resource: Resource); data object Missing; data object Failed }
fun fetch(request: Request, budget: Budget? = null): Fetched          // never throws
fun image(url: String, budget: Budget): Resource?                    // remote content of a message; a wrapper
```
`hop` must accept the first URL and every redirect target; `HostRules` and, when asked, the tracker list apply at
every hop. `Missing` = the server has no usable resource (HTTP 4xx other than 408 and 429, a refused type, too
large). `Failed` = no answer about the resource (I/O error, 408, 429, 5xx, the deadline, a spent budget, too many
redirects, a blocked hop).

### 11.3 Links (`LinkCleaner`, `Idn`, `Psl`)
- `shouldOverrideUrlLoading` always returns true. Plain-text bodies use the same link sheet (no autoLink).
- Cleaning: unwrap `google.com/url` (`q`, `url`; the host must be `google.<ICANN suffix>` or `www.` plus it, by the
  PSL), `*.safelinks.protection.outlook.com` (`url`),
  `urldefense.com/v3/__…__`, `*.awstrack.me` path, Mandrill `track/click`; strip `utm_*` and `gclid gclsrc
  dclid _ga fbclid igshid msclkid mc_cid mc_eid yclid twclid gbraid wbraid _hsenc __hssc __hstc __hsfp
  hsCtaTracking ml_subscriber vero_id oly_anon_id oly_enc_id icid ef_id s_cid`.
- After unwrapping (`privacy PS5`): parse again; allow only `http`/`https` (`mailto` → composer); reject or
  warn on userinfo, `\`, whitespace and controls; rebuild the URL from the parsed parts; show and open that
  exact string with `Intent(ACTION_VIEW, uri).addCategory(CATEGORY_BROWSABLE)` (no component, no selector).
- Sheet: registrable domain (PSL, `privacy PS6`) in bold, full cleaned URL, punycode for non-ASCII hosts,
  warnings (link text shows another registrable domain; IP host; mixed-script host; a non-ASCII label of only
  letters that look like ASCII letters, Cyrillic, Greek, Armenian or Latin such as `ɡ ı ɑ`). Buttons: Open, Copy,
  Open original.
- `Psl`: matcher over `public_suffix_list.dat` (ICANN + PRIVATE, wildcard and exception rules), loaded lazily.
  `matchedRule(host)` gives the rule that decides the public suffix, with its section (`private` = the PRIVATE
  DOMAINS part); null when only the implicit `*` rule matches (an unlisted suffix). Sender logos use it (§12).

### 11.4 Sender checks (`SenderCheck`)
- Authentication (`privacy PS4`): Gmail only. Parse only the **first** `Authentication-Results` header and use
  it only if its authserv-id is `mx.google.com`: `dmarc=pass` → `auth = 1`; `dmarc=fail`, or `spf=fail` and
  `dkim=fail` → `auth = 2`. Banner only for `auth = 2`. Other servers (Riseup too): no indicator.
- Display name contains an address or a registrable domain different from the sender's; `Reply-To`
  registrable domain differs; first message from this address (local DB, subtle).
- "Always show remote content from this sender" and one-click unsubscribe POST: Gmail only when `auth = 1`
  and the message is not in Junk. Other servers (Riseup too): per-sender trust is From-only; the toggle says
  "A sender can be faked"; no one-click POST (link sheet instead).
- The trust stores where the user set it (`trusted_sender.verified`, `TrustedOn`): a trust set on a VERIFIED
  message counts only on VERIFIED messages, so it does not load remote content on another account where From
  can be forged and the user never saw the note (`PLICO-PRIV-003`). A trust set on a FROM_ONLY message counts on
  both (`trustedFor`).
- `ListUnsubscribe` is the one parser of `List-Unsubscribe` for the UI and the POST: the URIs in angle
  brackets, else the comma-separated values; the first https (else http) page and the first mailto with an
  address. One-click needs an https URI and `List-Unsubscribe-Post`; the POST re-checks the trust rule.

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
- No analytics, crash reporting, downloadable fonts, BIMI or Gravatar fetches. No fetch at sync time.
- Hosts contacted (About lists them from the accounts and the settings):
  - the IMAP and SMTP servers of each account;
  - `accounts.google.com`, `oauth2.googleapis.com` when a Gmail account signs in with OAuth or the device
    account, or a Google client ID is set; `login.microsoftonline.com` when a Microsoft account signs in or a
    Microsoft client ID is set;
  - at setup only: the two autoconfig URLs of the mail domain (HTTPS) and DNS SRV and MX queries through the
    system resolver (§6.3); only the domain, never the address;
  - the logo source, only when sender logos are on: `icons.duckduckgo.com`; or `www.google.com` and
    `*.gstatic.com`; or, with the website source, the website of each sender and the hosts it redirects to;
  - remote content and unsubscribe requests after a tap.
- Gmail: a one-time note that Gmail writes the sender's public IP into sent mail.

### Sender logos (`SenderLogos`, `LogoRules`)
Optional, **off by default** (Settings → Message list, §13). Without them the avatar shows the first letter.
- Sources (`Prefs.LogoSource`), each gets only the domain, never the address:
  DuckDuckGo (default) `https://icons.duckduckgo.com/ip3/<domain>.ico`; the sender's website
  `https://<domain>/apple-touch-icon.png`, then `https://<domain>/favicon.ico`; Google
  `https://www.google.com/s2/favicons?domain=<domain>&sz=64` (redirects to `*.gstatic.com`). DuckDuckGo and
  Google requests may reach only their own hosts, also after a redirect (`LogoRules.hopFor`): through them a
  sender never sees the address of the phone. The website source may follow the redirects of the sender's site.
- Domain (`LogoRules.domainOf`): the host after the last `@`, to ASCII, lower case; then the PSL registrable
  domain. No logo domain for an IP address, a single-label or local host, a mixed-script or lookalike name, a
  suffix that the PSL does not list, a PRIVATE PSL suffix (free per-user domains such as `<id>.github.io`,
  `*.pages.dev`, `*.netlify.app`: a sender could make one domain per recipient), and the domains of the provider
  table (`isMailboxDomain`: every sender at gmail.com would get the same logo).
- Gate (the list and the message view use the same rule): only received mail. Never for sent rows and drafts
  (no recipient domain is ever sent), never for a From that is an address or an identity of any account of the
  user (a copy to self, or spam with a forged own From; also while the identities are not read yet), never in
  Spam, never Gmail mail without a DMARC pass (`SenderCheck.trust` ≠ NONE). The logo is always the one of
  From. Accepted risk, stated in the setting note: a forged From on a server other than Gmail can show the real
  logo of a known sender.
- First contact, **website source only**: a fetch only when the user made a sign: the sender is a contact, a
  trusted sender (§11.4), or the user read an earlier message from it (`hasEarlierSeenFrom`). An earlier received
  message is not sufficient, because a sender can send two (`PLICO-PRIV-001`). A first contact gets only a logo
  that is already cached.
  Else the sender's server would see when the user reads a first message (a read receipt) and could track
  per recipient. DuckDuckGo and Google fetch for first contacts too: the sender sees only the service.
- When: only rows on the screen fetch (metered networks too: at most 256 KiB per URL, cached 30 days). The message
  view uses only the cache (`cached()`): opening a message never fetches, because that would be a read
  receipt. Never in notifications, account avatars or compose recipients.
- Fetch: `RemoteFetcher.fetch` (§11.2) with `Accept: image/*`, image types only, 256 KiB and its own byte budget
  per URL, the tracker list on, at most 3 redirects; one fetch per domain at a time (a `Mutex` per domain; the
  waiting caller reads the disk again first), 3 domains in parallel (a `Semaphore`). All work runs in the row's
  coroutine on `Dispatchers.IO`: memory and disk answers never wait behind a slow fetch, and a row that leaves the
  screen while its fetch waits for a turn makes no request. A fetch that has started ends and fills the cache.
- Decode: the first bytes must be PNG, JPEG, GIF, WebP or ICO (`00 00 01 00`); SVG, HTML and everything else is
  never decoded. `ImageDecoder` must report the same format (ICO: `image/x-ico`, which Android reports, or
  `image/x-icon`, `image/vnd.microsoft.icon`); both sides from 32 to 512 px; scaled to at most 128 px, aspect
  kept, software memory; ICO gives its largest image. Bytes that do not decode count as "no logo at this URL".
- Cache: memory 8 MiB (LRU by bytes), keyed by the lower-case host of the address, so that `cached(email)` needs
  no PSL and no I/O. Disk `cacheDir/logos/<sha256(domain)>.png` (re-encoded PNG, never the bytes of the server)
  and `<sha256(domain)>.none` ("no logo"). A logo stays 30 days, "no logo" 7 days (by file time; a time in the
  future counts as expired). Only a `Missing` answer (also no URL with a usable image) writes `.none`; a `Failed`
  fetch (offline, captive portal, 5xx) blocks the domain for 10 min in memory only.
- `clear()` deletes memory and disk and starts a new generation: a fetch that ends under an older generation
  writes nothing. It runs when the user turns logos off, changes the source, and removes an account. At app
  start, `prune(enabled, anyAccount)` deletes expired files, or every file when logos are off or no account
  exists.

### Manifest (`apis AF11`)
Permissions: `INTERNET ACCESS_NETWORK_STATE POST_NOTIFICATIONS FOREGROUND_SERVICE
FOREGROUND_SERVICE_SPECIAL_USE RECEIVE_BOOT_COMPLETED WAKE_LOCK REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
SCHEDULE_EXACT_ALARM USE_BIOMETRIC`. Never: `GET_ACCOUNTS USE_EXACT_ALARM ACCESS_LOCAL_NETWORK READ_MEDIA_*
REQUEST_INSTALL_PACKAGES`. Components: `MainActivity` (exported: launcher, SEND, SEND_MULTIPLE, SENDTO
mailto, VIEW mailto BROWSABLE; singleTop), alias `.OpenMessage` (not exported), `OAuthRedirectActivity`
(exported; both redirect forms, §6.5), `PushService`, `StartReceiver`, `AlarmReceiver`, `ActionReceiver` (all
not exported), `FileProvider`. `<queries>`: `VIEW` with the app's own scheme, with no host and with any host
(`*`), for the redirect hijack check (§6.5).

## 13. UI (`ui`)
Material 3 expressive (`MaterialExpressiveTheme`), dynamic colour, day/night, edge-to-edge, predictive back.
Account colours from a palette of 8. `Route`: `Setup`, `Mailbox(view)`, `Message(id)`, `Compose(draftId, fresh)`,
`Drafts(accountId)`, `Settings`, `AccountSettings(id)`, `About`, `Reauth(accountId, editServers)`; `View = Unified | Folder(id) | Starred(accountId?) | Search(query, accountId?)`.

- **Setup** (`SetupState`, a pure state machine; `SetupViewModel` does the work). No page names a provider
  before discovery.
  - **Welcome**: lock icon, title, the privacy promise, one address field (`name@example.org`), "Next" and the
    text button "Enter the server settings yourself". Both check the address (a full address with a usable
    domain; no bare name). Next: an address that is already added → "already added" (before any network
    request); a probable typo (§6.2) → "Did you mean X? If your address is correct, select Next again." with
    "Use X" (replaces the domain and continues); then "Plico looks for the server settings…" with Cancel
    (discovery, §6.3). Result: a refusal → Welcome with "X does not let email apps connect with IMAP" or "…
    only through its Bridge app"; Google → Google page; Microsoft → Microsoft page; Riseup and other servers →
    Password page; nothing usable → Server settings. "Enter the server settings yourself" opens Server
    settings with the guess.
  - **Server settings**: a text by source (nothing found; found through DNS, make sure that they are correct;
    or the help pages of the provider), the table notes (iCloud user name, app password, IMAP access), the
    groups "Incoming server (IMAP)" and "Outgoing server (SMTP)" with server, port and security (SSL/TLS |
    STARTTLS; there is no setting without encryption), the user name ("Keep this empty to use your email
    address") and the note "Plico always uses encryption". A change of security switches a default port to
    the default of the other mode (993 ↔ 143, 465 ↔ 587). Hosts show and are edited in ASCII form. Next checks
    each field (no IP address: its certificate cannot be checked; no `host:port`; a usable name; port
    1..65535; control characters are removed from the user name). A pair of one built-in host (Google,
    Microsoft, Riseup) and one other host is refused ("One of these servers is a Microsoft server …");
    built-in hosts open their provider's page with the built-in servers.
  - **Password** ("Sign in"): "Account: <address>" (username autofill semantics, so that password managers
    pair it), "Plico connects only to <host> and <host>." (or one host) in ASCII form, the table notes, the
    password field, "Sign in", and "Change server settings" (other servers only). The user-name retry of
    §6.3.
  - **Google account**: the app-password card always (with a link to
    `https://myaccount.google.com/apppasswords`; no own address field: the typed address is the login hint);
    "Google account on this phone" and "Sign in with browser" cards when a Google client ID is set; "Use your
    own Google OAuth client" with the guide.
  - **Microsoft account**: "Outlook.com and Microsoft 365 accounts sign in through Microsoft in your
    browser"; the Outlook.com IMAP note for the consumer domains; the browser card when a Microsoft client ID
    is set, else the note that Plico needs one; "Use your own Microsoft client" opens the own-client page (the
    Entra steps, the redirect URI with a copy button, a link to `https://entra.microsoft.com/`, a field with
    the GUID check).
  - Back: from Password, Google or Microsoft reached from Server settings → Server settings; from Server
    settings, Google or Microsoft → Welcome (the address stays); own client → its provider's page; Cancel
    during detection → Welcome; saving cannot be cancelled.
  - Connection test (§6.6) → options (label, sender name, "Keep all mail offline" with a storage note; notes
    of the test) → permission prompts (§8.2) → mailbox.
  - Error texts: a refused password (every provider except Gmail; Gmail keeps the app-password texts);
    `NO_TLS` "The server did not start an encrypted connection … Make sure that the port and the security
    setting are correct." for a `StartTlsException` in the cause chain. For other servers only, also for a
    `TlsHandshakeException`, and for a `PlaintextTimeoutException` (STARTTLS mode: the greeting or the answer to
    STARTTLS did not come, §4.1). On the built-in servers of Riseup, Gmail and Microsoft these are network
    problems (their TLS modes are known; resets and captive portals cause them). A connect timeout and a read
    timeout after the switch to TLS (LOGIN, LIST, EXAMINE, SMTP AUTH) are always network problems.
    A certificate failure (`MailError.Tls`): on the built-in servers `TLS` ("… A network that examines
    connections … can cause this. Try again on a different network."); on other servers `TLS_NAME` ("… Often the
    certificate is for a different server name. Make sure that the server name is correct. Use the server name
    that your mail provider gives. If the name is correct, try again on a different network."), because there a
    certificate for another name (shared hosting, the guessed `imap.<domain>`) is the usual cause.
  - **Sign in again** (`Route.Reauth`): starts on the provider's page, or with `editServers` ("Change server
    settings" of an account on other servers with a password) on Server settings with the stored servers. The
    test writes the servers when they or `idle` changed (`ServerUpdate`; also a user name that the retry
    found, or a Microsoft login); an IMAP host or port change first stops the session and then wipes the
    synced data (§5). Servers of another provider are refused ("These are the servers of X. Remove this
    account and add it again."). "Sign in again" never revokes after the new consent.
- **Mailbox**: search-bar top bar with drawer button. Drawer: All inboxes, Starred, then per account Inbox,
  Sent, Drafts, Archive (not Gmail) / All mail (Gmail), Important (Gmail), Spam, Trash, other folders/labels under
  "More"; unread counts on every entry; long-press a folder → "Notify for new mail". Rows: avatar, sender,
  date, subject, 2-line snippet, star, paperclip. The list reads its look once (`RowLook`: unread style,
  sender colours, logo source or null; the stored values are the initial values, so there is no flicker) and
  gives it to every row; rows never read settings.
  - Unread style ("Mark unread mail", default on): unread → sender bold, subject semi-bold, date bold in
    `primary`, an 8 dp `primary` dot before the date; read → sender, subject and date in normal weight and
    `onSurfaceVariant`. The snippet is always `onSurfaceVariant`; a draft's sender stays `error`. Off: unread and
    read rows have the same text (normal weight, no dot, date in `onSurfaceVariant`). TalkBack says "Unread" and
    the account label as before.
  - Avatar colour ("Colour for unread mail", default on): unread → a colour per sender, read → the neutral
    `secondaryContainer`, so the colour shows the read state. The key (never sent anywhere) is the lower-case
    From address, the first recipient for sent rows, none for drafts. `SenderColors.index` = the MurmurHash3
    finalizer over `String.hashCode()` of the key, modulo the 8 colours of the contrast-tested account palette
    (light tone 40 + white, dark tone 80 + tone 20; independent of the dynamic colours); without the mix, similar
    addresses shared few colours. Off: always neutral.
  - Account dots ("Account dots", default on): in lists of more than one account, the account colour is a 12 dp
    dot with a 2 dp `surface` border at the bottom end of the avatar (decorative). Off: no dot; TalkBack still
    says the account label.
  - With sender logos (§12) the avatar keeps its fill as a 3 dp ring (so the read state stays visible) around a
    white disc; the logo fills the disc with a 4 dp inset, `ContentScale.Fit`, clipped to a circle, whatever the
    size of the source image.
  Swipe (`apis AF4`): per row `remember(msg.id) { SwipeToDismissBoxState(Settled) { it * 0.4f } }`, `onDismiss`
  captures only the id; toggle actions reset the state first, then apply; defaults start→end Archive, end→start
  Delete; choices Archive, Delete, Read/Unread, Star, None; Undo snackbar. Long-press multi-select: Select all,
  Archive, Delete, Read/Unread, Star, Move/Labels, Spam/Not spam. Trash and Spam overflow: Empty (confirm).
  Pull to refresh. Extended FAB "Compose". Banners: sign-in needed, push paused/late, guard, Gmail setup state,
  messages not sent. Rows of sent mail (in Sent, or from the account address) show "To: <first recipient>".
  An account with local drafts and no Drafts mailbox gets a Drafts entry that opens its local drafts.
- **Message**: top bar (back, Archive, Delete, Unread, overflow: Move/Labels with "New…", Spam/Not spam, Star,
  Important (Gmail), Unsubscribe, View headers (`BODY.PEEK[HEADER]` on demand, shows the sender's Date)).
  Subject, Gmail label chips, sender block (name + real address, date, to/cc/bcc expandable; the logo only from
  the cache, with the gate of §12), sender-check
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
- **Settings**: Accounts, Notifications (hide content), Privacy & security (remote content default, confirm
  links, dark email, app lock + timeout, hide in recents, block screenshots, incognito keyboard), Mail (swipe
  actions, undo send seconds), Message list, Sign-in clients, Push & battery (§8.2), About.
  - Accounts: add; per account, in this order: header (sign-in method, e.g. "Signs in with Microsoft in the
    browser"), profile (label, sender name, signature, colour), identities (the note by provider: Gmail, Riseup,
    or "Add only addresses that your mail provider lets you send from"), offline (body days / all offline),
    new mail (push mode + poll interval, measured server keepalive and cost; a server without IDLE shows "This
    server cannot tell Plico about new mail immediately …" and the poll interval only), notifications (channel
    link) + Gmail categories, **Servers** (Incoming and Outgoing as `host:port · SSL/TLS|STARTTLS` in ASCII
    form, the user name when it is not the address, "Change server settings" for other servers with a
    password), **Folders** (archive folder) and **Storage** (quota) for every account except Gmail (the quota
    note only on Riseup accounts), Riseup web settings links (filters, vacation, aliases, spam; Riseup only),
    Gmail notes (Send-as Jan 2027, IP in headers; Gmail only), sign in again, remove (a Microsoft OAuth account
    adds the note how to end the grant, §6.4).
  - **Message list**: "Mark unread mail" (on), "Colour for unread mail" (on), "Account dots" (on), "Sender logos" (off), "Logo
    source" (enabled only with logos: DuckDuckGo, Website of the sender, Google, each with what it gets), and
    with logos on a note: "A logo does not prove who sent a message …". Turning logos off or a new source
    deletes the cached logos.
  - **Sign-in clients**: "Google client ID" (guide unchanged) and "Microsoft client ID" (GUID check, guide with
    the redirect URI); shown only when an account uses one of them or one is set (debug builds preset both).
    When accounts use the client, its dialog says that they must sign in again after a change.
  - About: version, the tagline "A private and simple email app.", licences (Plico GPL-3.0 and the bundled
    code), what the app connects to (built from the accounts and settings, §12), the CT note.

## 14. Errors
`:mail` throws `IOException` subclasses: `ImapNoException(code, text)`, `ImapBadException`,
`ImapByeException`, `ImapProtocolException`, `ImapMissingCapabilityException`, `SmtpException`,
`SmtpRecipientException`, `SmtpUncertainException`, `SmtpAuthUnavailableException`, `StartTlsException`,
`TlsHandshakeException`, `SSLException`. The app maps them to `MailError`: `AuthRequired`
(`[AUTHENTICATIONFAILED]`, SMTP 535, `invalid_grant`, consent intent), `Tls` (§8.3), `Network`, `Throttled`,
`Server(text)`, `Setup(text, cause)`.

`MailErrors.classify(e, networkValidated)`, in this order:
- A certificate or hostname failure in the cause chain → `Tls` when the network is validated, else `Network`
  (captive portal). `StartTlsException` → the same rule. `TlsHandshakeException` and every other
  `SSLException` → `Network`.
- `ImapNoException`: the Exchange text "authenticated but not connected" → `Setup` (before the sign-in codes:
  a sign-in again cannot help; at runtime the account shows a setup problem and retries with the throttled
  backoff, not the endless network backoff); `AUTHENTICATIONFAILED`, `AUTHORIZATIONFAILED`, `EXPIRED` →
  `AuthRequired`; `UNAVAILABLE`, `INUSE`, `THROTTLED`, `OVERQUOTA` or a throttle text → `Throttled`; else
  `Server`. A refused AUTHENTICATE without a code (or with ALERT) → `AuthRequired`.
- `ImapByeException`: throttle → `Throttled`, else `Network`. `ImapBadException` → `Server`.
- `ImapMissingCapabilityException`, `SmtpAuthUnavailableException` → `Setup`.
- `SmtpException`: enhanced `5.7.139` or `5.7.30` (Microsoft SMTP AUTH off) → `Setup`, before the 535 rule (no
  sign-in loop); 535, or 5xx at AUTH → `AuthRequired`; other 4xx → `Throttled`; else `Server`.
- `SmtpRecipientException`, `SmtpUncertainException` → `Server` (never retried as a network error); every other
  IOException → `Network`.
- Throttle texts: "too many simultaneous connections" and "bandwidth limits" (Gmail), "maximum number of
  connections" (Dovecot), "too many connections", "connection limit".

`MailErrors.localized(context, error)` gives a user text where the server text alone does not help: a `Setup`
with the Exchange NO in its cause chain → "Microsoft did not open the mailbox. IMAP must be on for this account.
In Outlook.com, turn on IMAP for devices and apps …"; a token refused for a server that is not a host of its issuer → "Plico cannot use this
sign-in method with this server …". The connection test and `AccountSession.fail` call it.

## 15. Build and tests
- Gradle 9.8.1, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, JDK 21. arm64-v8a. Checks:
  `./gradlew :mail:testDebugUnitTest :app:testDebugUnitTest :mail:lintDebug :app:lintDebug :app:assembleDebug`.
  Release: R8 + resource shrinking, `dependenciesInfo` off, `vcsInfo` off.
- `:mail` tests: tokenizer (literals, sections, resp codes, real Gmail and Dovecot lines), scripted fake server
  (greeting, SASL-IR, literals incl. tagged NO instead of `+`, IDLE: kick before IDLE, kick between IDLE and
  `+`, EXISTS before `+`, keepalive, BYE), BODYSTRUCTURE (Gmail, Dovecot, Apple Mail mixed html, Mailman footer,
  message/rfc822), modified UTF-7, UidSet, encoded words, addresses, RFC 2231, dates, QP/base64 incl. streams,
  MessageBuilder (no banned headers, UTC date, Message-ID, Bcc only with includeBcc, CRLF, folding, CR/LF
  injection in subject/name/filename/References), SMTP (multi-line replies, auth, dot-stuffing, RCPT
  rejection → RSET, 4xx/5xx, address validation). Also:
  - `MailSocketTest` with a loopback STARTTLS server: the upgrade (TLS 1.3, SNI, hostname hook, `encrypted`
    false → true), compression after STARTTLS, bytes after the reply → `StartTlsException` and a closed socket,
    a wrong name or an untrusted certificate after the upgrade, implicit TLS to a plaintext server →
    `TlsHandshakeException`, the 20 s read-timeout cap before TLS, non-ASCII hosts.
  - `ImapClientTest`: STARTTLS (CAPABILITY again, pre-TLS `LOGINDISABLED` ignored, injection, NO/BAD, PREAUTH,
    real TLS), PLAIN with and without SASL-IR, LOGIN as strings and literals, LOGINDISABLED, XOAUTH2 inline and
    bare, no secret before TLS, the IMAP4rev2 base set and UTF-8 names, LIST RETURN only with LIST-EXTENDED, MOVE
    fallback (also with the caller's expunge), guarded expunge (with the caller's own leftovers allowed), plain
    search (union, summed COUNT, MAX, an ESEARCH answer), SELECT without UIDNEXT, NAMESPACE, ID, log redaction,
    `PlaintextTimeoutException` only before TLS.
  - `SmtpClientTest`: STARTTLS with the second EHLO (extensions only after TLS), 454/502 → no AUTH, injection
    after 220, both AUTH line forms, AUTH LOGIN, no usable mechanism, XOAUTH2 at the 510-octet limit, no secret
    before TLS, `PlaintextTimeoutException` only before TLS.
- `:app` JVM tests: KeepalivePolicy (also the scope per server), Scheduler due-time math, LinkCleaner, Idn, Psl
  (also the PRIVATE section and `matchedRule`), HtmlSanitizer, SenderCheck, Files (`safeName`, logo paths),
  notification rule, IncomingUri/mailto parsing, Schema (v1 and the migration block as written here). Also:
  - account: `ProviderTableTest` (domains, MX suffixes, pinned host sets, mixed pairs, refusals, typo check incl.
    real regional domains and real domains near table domains), `AutoconfigTest` (Thunderbird samples, the esiliati.org file, TLS by port, plain and
    POP3 skipped, OAuth2 only for Google/Microsoft, DOCTYPE and UTF-16 refusal, size cap, local hosts, no local
    part in URLs or host names), `DnsWireTest` (SRV/MX order, compression, pointer loops, bounds, TC, RCODE),
    `DiscoveryTest` (every step of §6.3, mixed pairs, pinning, stale autoconfig + Google MX, the 10 s timeout,
    no request for table domains), `BrowserOAuthTest` (both issuers: URL parameters, redirect forms, pending
    entries, identity, AADSTS mapping, `otherHandlers`), `OAuthCredentialsTest` (host check before any token,
    rotation stored before use, Microsoft SMTP refusal), `AccountCredentialsTest`, `MailErrorsTest`,
    `AccountProbeTest` (Microsoft SMTP off with real `OAuthCredentials`, Gmail server for another provider,
    Exchange "not connected" and a refused new token, the failed endpoint), `PasswordCredentialsTest`,
    `CredentialsCacheTest`.
  - sync, push, notify: `FolderRulesTest` (generic rules, localized names, namespaces, personal root, Riseup
    exact), `MailboxMathTest` (skip without CONDSTORE, search windows), `SyncPlanTest`, `PushModesTest`,
    `TextsTest` (default labels), `HeaderParserTest` (`\Deleted`, never on Gmail).
  - ui: `SetupStateTest` (every transition and back path, the Microsoft sign-in-again account check),
    `SetupInputTest`, `ProblemTest` (NO_TLS only for other servers and only before TLS, TLS_NAME for other
    servers), `OAuthResumeTest`, `SettingsModelTest` (connections, sign-in clients),
    `NavigatorTest`/`EntryTest` (`Reauth(editServers)`), `RowLookTest`, `SenderColorsTest`, `ListContextTest`
    (logo gate).
  - html: `LogoRulesTest` (domains, URLs, hop rule, sniffing, cache times), `SenderLogosTest` (the cache with
    a fake network: first-contact rule, Missing/Failed, generations, one budget per URL, cache reads and rows
    that leave never wait behind slow fetches).
- Device tests (`app/src/androidTest`) run only on the `megaglass-api35` emulator, always with
  `ANDROID_SERIAL=emulator-5554` (a connected phone with the user's install would be wiped):
  `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`. They include the DB tests (`DbTest`,
  `AccountDaoTest` with the server-change wipe, `MigrationTest` v1 → v4 and v2 → v4, `MessageDaoTest` with `\Deleted` rows),
  the sync tests against `SyncFakeServer` (it answers only the extensions that it advertises): Gmail, Riseup,
  `GenericSyncDeviceTest`, `GenericOpsTest`, `SenderTest` (Sent copy rules), `DraftsTest` (no APPENDUID), and
  `app.plico.sync.fallback` (servers with only IMAP4rev1: no MOVE, UIDPLUS, ESEARCH, CONDSTORE or UIDNEXT),
  `AutoconfigDeviceTest` (the Android DOM parser) and `LogoDecodeTest` (the `ImageDecoder` rules).
- Emulator rule: type only addresses that need no lookup or cannot resolve (table domains such as
  `x@gmail.com`, `x@outlook.com`, and `user@example.invalid`); manual servers only under `.invalid`; never tap
  a browser sign-in. Live tests with real accounts only on the user's phone, after the user agrees, with the
  user typing the credentials (`docs/LIVE-TESTS.md`).

## 16. Live-test list (needs the user's accounts)
The steps are in `docs/LIVE-TESTS.md`.
1. Gmail: a label-only change (archive in Gmail web) raises MODSEQ in All Mail (`protocol PS17`). Fallback if
   not: on INBOX `Changed`, compare INBOX EXISTS with the local `\Inbox` count; differ → `UID SEARCH RETURN
   (ALL) X-GM-RAW "in:inbox"` in All Mail and fix memberships.
2. Gmail: unstarring a web-set star with `-FLAGS (\Flagged)` (`protocol PS25`).
3. Gmail: IDLE behaviour (server timeout, keepalives) and the 9-min start value.
4. Riseup: post-auth CAPABILITY, IDLE keepalive interval, `Authentication-Results` authserv-id, no duplicate in
   Sent after APPEND, EHLO in the first Received header.
5. Browser OAuth with a new client ID (custom scheme accepted?) and the device-account token for a registered
   client.
6. Setup with one field: the Riseup account and the Gmail account get their built-in servers; after the update from
   schema v1 the existing accounts keep working and keep their learned keepalive intervals.
7. A small IMAP server (esiliati.org, the user's account): autoconfig gives `imap.esiliati.org:993` and
   `mail.esiliati.org:465`; sync, IDLE or polling as the capabilities allow, send, one copy in Sent; STARTTLS
   on 143/587 through "Change server settings"; the NO_TLS text for SSL/TLS on 587 and STARTTLS on 465.
8. If available: a Google Workspace domain (found by MX), Fastmail/mailbox.org/Posteo, an autoconfig-only domain,
   Yahoo or AOL (one Sent copy), Outlook.com and Microsoft 365 with the user's Entra client.
9. The message list in both themes, sender logos with each source, logos offline and online.

Facts from documentation that only a live test can confirm before release: the Entra redirect URI form
`app.plico://oauth2redirect` (if Entra refuses it, the fallback `msauth.app.plico://auth` needs a second manifest
scheme, a third redirect form and a new `OAuthIssuer.MICROSOFT.redirectUri`); Microsoft Sent Items behaviour (no
APPEND); the Microsoft size limit (25 MB); the Exchange IDLE timeout; `smtp-mail.outlook.com` or
`smtp.office365.com` for personal accounts; the command on which Exchange sends "authenticated but not
connected"; Yahoo and AOL save SMTP mail in Sent; WEB.DE SMTP on 587; NetEase accepts the minimal ID; the iCloud
custom-domain user name; the answers of DuckDuckGo and Google for a domain without an icon.
