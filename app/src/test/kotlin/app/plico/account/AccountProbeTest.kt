package app.plico.account

import app.plico.R
import app.plico.data.FolderRole
import app.plico.data.Provider
import app.plico.data.Servers
import app.plico.mail.imap.ImapClient
import app.plico.mail.imap.ImapConnection
import app.plico.mail.imap.ImapMissingCapabilityException
import app.plico.mail.imap.MailboxInfo
import app.plico.mail.net.Endpoint
import app.plico.mail.net.TlsMode
import app.plico.mail.smtp.SmtpAuthUnavailableException
import app.plico.mail.smtp.SmtpClient
import app.plico.sync.FolderRules
import kotlinx.coroutines.test.runTest
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountProbeTest {
    @Test
    fun `every Gmail mailbox that sync needs stops setup or adds a note`() {
        // A hidden All Mail stops setup; every other required mailbox has a note.
        assertEquals(FolderRules.GMAIL_REQUIRED - FolderRole.ALL, AccountProbe.MISSING_NOTES.keys)
    }

    @Test
    fun `Gmail probe finds hidden mailboxes as sync does`() {
        val all = MailboxInfo("[Gmail]/All Mail", "/", setOf("\\HasNoChildren", "\\All"))
        val trash = MailboxInfo("[Gmail]/Bin", "/", setOf("\\HasNoChildren", "\\Trash"))
        val junk = MailboxInfo("[Gmail]/Spam", "/", setOf("\\HasNoChildren", "\\junk"))
        val inbox = MailboxInfo("INBOX", "/", setOf("\\HasNoChildren"))
        fun notes(vararg list: MailboxInfo) =
            FolderRules.gmail(0, list.toList()).missing.mapNotNull { AccountProbe.MISSING_NOTES[it] }

        assertEquals(emptyList(), notes(inbox, all, trash, junk))
        assertEquals(listOf(R.string.account_note_gmail_trash_hidden), notes(inbox, all, junk))
        assertEquals(
            listOf(R.string.account_note_gmail_trash_hidden, R.string.account_note_gmail_spam_hidden),
            notes(inbox, all),
        )
        // A \Noselect All Mail cannot be synced, so the probe treats it as hidden too.
        val noselect = all.copy(attributes = all.attributes + "\\Noselect")
        assertEquals(setOf(FolderRole.ALL), FolderRules.gmail(0, listOf(inbox, noselect, trash, junk)).missing)
    }

    private val texts = AccountProbe.Texts({ "text-$it" }) { id, count -> "plural-$id-$count" }
    private val imapStreams = ArrayList<CannedStream>()
    private val smtpStreams = ArrayList<CannedStream>()

    private val microsoft = Servers(
        Endpoint("outlook.office365.com", 993, TlsMode.IMPLICIT),
        Endpoint("smtp-mail.outlook.com", 587, TlsMode.STARTTLS),
    )
    private val other = Servers(Endpoint("imap.example.org", 993, TlsMode.IMPLICIT), Endpoint("smtp.example.org", 465, TlsMode.IMPLICIT))

    /** The IMAP lines of a login with [caps], a LIST and an EXAMINE INBOX; tags follow [first]. */
    private fun mailbox(caps: String = "IMAP4rev1 IDLE MOVE UIDPLUS", first: Int = 1): Array<String> = arrayOf(
        "A$first OK [CAPABILITY $caps] Logged in",
        "* LIST (\\HasNoChildren) \"/\" INBOX",
        "A${first + 1} OK LIST completed",
        "* 3 EXISTS",
        "* OK [UIDVALIDITY 14] UIDs valid",
        "* OK [UIDNEXT 9] Predicted next UID",
        "A${first + 2} OK [READ-ONLY] EXAMINE completed",
        "* BYE Logging out",
        "A${first + 3} OK LOGOUT completed",
    )

    private val smtpGreeting = arrayOf("220 ready", "250-smtp.example.org", "250 AUTH PLAIN LOGIN XOAUTH2")

    private suspend fun probe(
        provider: Provider,
        servers: Servers,
        credentials: Credentials,
        imapLines: Array<String>,
        smtpLines: Array<String>,
    ): ProbeResult = AccountProbe.probe(
        texts, provider, servers, credentials,
        imapConnect = { endpoint ->
            // As ImapClient.connect: the greeting is read, so the capabilities before the login are known.
            val stream = CannedStream(IMAP_GREETING, *imapLines).also(imapStreams::add)
            ImapClient(ImapConnection(stream).apply { greeting() }, endpoint.host)
        },
        smtpConnect = { endpoint ->
            val stream = CannedStream(*smtpGreeting, *smtpLines).also(smtpStreams::add)
            SmtpClient(stream, host = endpoint.host).apply { start() }
        },
        validated = { true },
    )

    /** The classified error of a failed probe, after a check that the failure names [endpoint]. */
    private inline fun <reified T : MailError> failsAt(endpoint: Endpoint, block: () -> Unit): T {
        val failure = assertFailsWith<ProbeException>(block = block)
        assertEquals(endpoint, failure.endpoint)
        return assertIs<T>(failure.error)
    }

    @Test
    fun `a failure names the server that failed and keeps the cause`() = runTest {
        // STARTTLS chosen on the SSL/TLS port: the SMTP greeting does not come.
        val servers = other.copy(smtp = Endpoint("smtp.example.org", 465, TlsMode.STARTTLS))
        val failure = assertFailsWith<ProbeException> {
            AccountProbe.probe(
                texts, Provider.OTHER, servers, PasswordCredentials(Provider.OTHER, "me@example.org", "pw"),
                imapConnect = { ImapClient(ImapConnection(CannedStream(IMAP_GREETING, *mailbox())).apply { greeting() }, it.host) },
                smtpConnect = { throw SocketTimeoutException("Read timed out") },
                validated = { true },
            )
        }
        assertEquals(servers.smtp, failure.endpoint)
        assertIs<SocketTimeoutException>(assertIs<MailError.Network>(failure.error).cause)
    }

    private class Endpoint0 : TokenEndpoint {
        var refreshes = 0

        override suspend fun refresh(refreshToken: String): AccessToken =
            AccessToken("access-${++refreshes}", Long.MAX_VALUE / 2)

        override suspend fun revoke(token: String) {}
    }

    @Test
    fun `Microsoft without SMTP AUTH can still receive`() = runTest {
        val endpoint = Endpoint0()
        val credentials = OAuthCredentials(
            "me@outlook.com", { "refresh" }, endpoint, AccessToken("setup", Long.MAX_VALUE / 2),
            clock = { 0L }, hosts = OAuthIssuer.MICROSOFT.hosts, smtpOff = "smtp off",
        )
        val refused = "535 5.7.3 Authentication unsuccessful [AM0PR01CA0001.eurprd01.prod.exchangelabs.com]"
        val result = probe(Provider.MICROSOFT, microsoft, credentials, mailbox(), arrayOf(refused, refused))
        assertEquals(ProbeResult(listOf("text-${R.string.account_note_smtp_off}"), idle = true), result)
        assertEquals(1, endpoint.refreshes)
        val off = "535 5.7.139 Authentication unsuccessful, SmtpClientAuthentication is disabled for the Tenant."
        val disabled = probe(Provider.MICROSOFT, microsoft, credentials, mailbox(), arrayOf(off))
        assertEquals(listOf("text-${R.string.account_note_smtp_off}"), disabled.notes)
    }

    @Test
    fun `another provider fails on an SMTP setup problem`() = runTest {
        val off = "535 5.7.139 Authentication unsuccessful"
        val error = failsAt<MailError.Setup>(other.smtp) {
            probe(Provider.OTHER, other, PasswordCredentials(Provider.OTHER, "me@example.org", "pw"), mailbox(), arrayOf(off))
        }
        assertEquals("535 5.7.139 Authentication unsuccessful", error.message)
    }

    @Test
    fun `a Gmail server is never another provider`() = runTest {
        val error = failsAt<MailError.Setup>(other.imap) {
            probe(
                Provider.OTHER, other, PasswordCredentials(Provider.OTHER, "me@example.org", "pw"),
                mailbox("IMAP4rev1 IDLE X-GM-EXT-1"), arrayOf(SMTP_OK),
            )
        }
        assertEquals("text-${R.string.account_error_gmail_server}", error.message)
    }

    @Test
    fun `a mailbox that Microsoft does not open stops setup`() = runTest {
        val lines = mailbox().take(3).toTypedArray() + "A3 NO User is authenticated but not connected."
        val credentials = OAuthCredentials(
            "me@outlook.com", { "refresh" }, Endpoint0(), AccessToken("setup", Long.MAX_VALUE / 2),
            clock = { 0L }, hosts = OAuthIssuer.MICROSOFT.hosts, smtpOff = "smtp off",
        )
        val error = failsAt<MailError.Setup>(microsoft.imap) {
            probe(Provider.MICROSOFT, microsoft, credentials, lines, arrayOf(SMTP_OK))
        }
        assertEquals("text-${R.string.account_error_ms_imap_off}", error.message)
    }

    @Test
    fun `Microsoft refusing the new token at IMAP stops setup with the IMAP text`() = runTest {
        // Exchange Online answers so when IMAP is off for the mailbox: a sign-in again cannot help.
        val credentials = OAuthCredentials(
            "me@contoso.com", { "refresh" }, Endpoint0(), AccessToken("setup", Long.MAX_VALUE / 2),
            clock = { 0L }, hosts = OAuthIssuer.MICROSOFT.hosts, smtpOff = "smtp off",
        )
        val m365 = microsoft.copy(smtp = Endpoint("smtp.office365.com", 587, TlsMode.STARTTLS))
        val error = failsAt<MailError.Setup>(m365.imap) {
            probe(Provider.MICROSOFT, m365, credentials, arrayOf("A1 NO AUTHENTICATE failed."), arrayOf(SMTP_OK))
        }
        assertEquals("text-${R.string.account_error_ms_imap_off}", error.message)
        // Another provider: a refused sign-in stays a sign-in problem.
        val refused = failsAt<MailError.AuthRequired>(other.imap) {
            probe(
                Provider.OTHER, other, PasswordCredentials(Provider.OTHER, "me@example.org", "pw"),
                arrayOf("A1 NO AUTHENTICATE failed."), arrayOf(SMTP_OK),
            )
        }
        assertEquals("AUTHENTICATE failed.", refused.message)
    }

    @Test
    fun `other servers must list and open INBOX`() = runTest {
        val result = probe(
            Provider.OTHER, other, PasswordCredentials(Provider.OTHER, "me@example.org", "pw"), mailbox(), arrayOf(SMTP_OK),
        )
        assertEquals(ProbeResult(emptyList(), idle = true), result)
        val sent = imapStreams.single().written().map { it.substringAfter(' ').substringBefore(' ') }
        assertEquals(listOf("AUTHENTICATE", "LIST", "EXAMINE", "LOGOUT"), sent)
        assertTrue(smtpStreams.single().written().any { it.startsWith("AUTH PLAIN") })
    }

    @Test
    fun `a server without IDLE polls and gets a note`() = runTest {
        val result = probe(
            Provider.OTHER, other, PasswordCredentials(Provider.OTHER, "me@example.org", "pw"), mailbox("IMAP4rev1"), arrayOf(SMTP_OK),
        )
        assertEquals(ProbeResult(listOf("plural-${R.plurals.account_note_no_idle}-15"), idle = false), result)
    }

    @Test
    fun `NetEase gets the minimal ID`() = runTest {
        val netEase = Servers(Endpoint("imap.163.com", 993, TlsMode.IMPLICIT), Endpoint("smtp.163.com", 465, TlsMode.IMPLICIT))
        val lines = arrayOf("A1 OK [CAPABILITY IMAP4rev1 IDLE ID] Logged in", "* ID NIL", "A2 OK ID completed") +
            mailbox(first = 2).drop(1)
        probe(Provider.OTHER, netEase, PasswordCredentials(Provider.OTHER, "me@163.com", "pw"), lines, arrayOf(SMTP_OK))
        assertEquals("A2 ID (\"name\" \"Mail\")", imapStreams.single().written()[1])
    }

    @Test
    fun `no usable password method has its own text`() = runTest {
        val noPlain = object : Credentials by PasswordCredentials(Provider.OTHER, "me", "pw") {
            override suspend fun authImap(client: ImapClient) = throw ImapMissingCapabilityException(setOf("AUTH=PLAIN"))
        }
        val error = failsAt<MailError.Setup>(other.imap) { probe(Provider.OTHER, other, noPlain, mailbox(), arrayOf(SMTP_OK)) }
        assertEquals("text-${R.string.account_error_auth_method}", error.message)
        val noAuth = object : Credentials by PasswordCredentials(Provider.OTHER, "me", "pw") {
            override suspend fun authSmtp(client: SmtpClient) = throw SmtpAuthUnavailableException()
        }
        val smtpError = failsAt<MailError.Setup>(other.smtp) { probe(Provider.OTHER, other, noAuth, mailbox(), arrayOf(SMTP_OK)) }
        assertEquals("text-${R.string.account_error_auth_method}", smtpError.message)
    }

    @Test
    fun `a wrong password is classified`() = runTest {
        val lines = arrayOf("A1 NO [AUTHENTICATIONFAILED] Authentication failed.")
        val error = failsAt<MailError.AuthRequired>(other.imap) {
            probe(Provider.OTHER, other, PasswordCredentials(Provider.OTHER, "me@example.org", "pw"), lines, arrayOf(SMTP_OK))
        }
        assertEquals("Authentication failed.", error.message)
    }

    @Test
    fun `a token for another host is refused with a clear text`() = runTest {
        val credentials = OAuthCredentials(
            "me@gmail.com", { "refresh" }, Endpoint0(), AccessToken("setup", Long.MAX_VALUE / 2),
            clock = { 0L }, hosts = OAuthIssuer.GOOGLE.hosts,
        )
        val error = failsAt<MailError.Setup>(other.imap) { probe(Provider.GMAIL, other, credentials, mailbox(), arrayOf(SMTP_OK)) }
        assertEquals("text-${R.string.account_error_sign_in_kind}", error.message)
        assertEquals(emptyList(), imapStreams.single().written())
    }
}
