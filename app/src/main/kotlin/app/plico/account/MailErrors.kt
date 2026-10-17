package app.plico.account

import android.content.Context
import app.plico.R
import app.plico.mail.imap.ImapBadException
import app.plico.mail.imap.ImapByeException
import app.plico.mail.imap.ImapMissingCapabilityException
import app.plico.mail.imap.ImapNoException
import app.plico.mail.net.StartTlsException
import app.plico.mail.net.TlsHandshakeException
import app.plico.mail.smtp.SmtpAuthUnavailableException
import app.plico.mail.smtp.SmtpException
import app.plico.mail.smtp.SmtpRecipientException
import app.plico.mail.smtp.SmtpUncertainException
import java.io.IOException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** Maps `:mail` and platform exceptions to [MailError] (DESIGN.md §8.3 "Error classes", §14). */
object MailErrors {
    private val AUTH_CODES = setOf("AUTHENTICATIONFAILED", "AUTHORIZATIONFAILED", "EXPIRED")
    private val THROTTLE_CODES = setOf("UNAVAILABLE", "INUSE", "THROTTLED", "OVERQUOTA")
    private val ALERT_CODES = setOf("ALERT", "WEBALERT")

    // Gmail: "Too many simultaneous connections", "Account exceeded command or bandwidth limits".
    // Dovecot: "Maximum number of connections from user+IP exceeded". Others: "too many connections",
    // "connection limit".
    private val THROTTLE_TEXT = Regex(
        "too many simultaneous connections|maximum number of connections|bandwidth limits|too many connections|" +
            "connection limit",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Exchange Online after a successful login, on a mailbox command: IMAP is off for the mailbox, or the user
     * signed in with an alias. A sign-in again does not help.
     */
    private val NOT_CONNECTED = Regex("authenticated but not connected", RegexOption.IGNORE_CASE)

    /** Microsoft: SMTP AUTH is off for the tenant or the mailbox (5.7.139), or basic auth is off (5.7.30). */
    private val SMTP_AUTH_OFF = setOf("5.7.139", "5.7.30")

    /**
     * Classifies [t]. [networkValidated] is whether the network of the failed connection has
     * NET_CAPABILITY_VALIDATED: a certificate failure behind a captive portal is a [MailError.Network].
     * Callers rethrow CancellationException before they call this.
     */
    fun classify(t: Throwable, networkValidated: Boolean): MailError = when {
        t is MailError -> t
        isCertificateFailure(t) -> if (networkValidated) MailError.Tls(t) else MailError.Network(t)
        // A refused or broken STARTTLS: like a certificate failure, a captive portal can cause it.
        t is StartTlsException -> if (networkValidated) MailError.Tls(t) else MailError.Network(t)
        // No TLS on this port (e.g. a plaintext server): the certificate cases are above.
        t is TlsHandshakeException -> MailError.Network(t)
        // Conscrypt reports ordinary resets as SSLException ("I/O error during system call").
        t is SSLException -> MailError.Network(t)
        t is ImapNoException -> when {
            NOT_CONNECTED.containsMatchIn(t.message.orEmpty()) -> MailError.Setup(text(t), t)
            t.code?.name in AUTH_CODES -> MailError.AuthRequired(text(t), cause = t)
            isThrottle(t.code?.name, t) -> MailError.Throttled(text(t), t)
            else -> MailError.Server(text(t), t)
        }
        t is ImapByeException ->
            if (isThrottle(t.code?.name, t)) MailError.Throttled(text(t), t) else MailError.Network(t)
        t is ImapBadException -> MailError.Server(text(t), t)
        t is ImapMissingCapabilityException -> MailError.Setup(text(t), t)
        t is SmtpAuthUnavailableException -> MailError.Setup(text(t), t)
        t is SmtpException -> when {
            // Before 535: a sign-in again cannot help, so no sign-in loop.
            t.enhanced in SMTP_AUTH_OFF -> MailError.Setup(text(t), t)
            t.code == 535 || (t.stage == SmtpException.Stage.AUTH && t.code in 500..599) ->
                MailError.AuthRequired(text(t), cause = t)
            t.code in 400..499 -> MailError.Throttled(text(t), t)
            else -> MailError.Server(text(t), t)
        }
        // Permanent for this message; an uncertain send must never be retried as a network error.
        t is SmtpRecipientException || t is SmtpUncertainException -> MailError.Server(text(t), t)
        t is IOException -> MailError.Network(t)
        else -> MailError.Server(text(t), t)
    }

    /**
     * [e] with a text for the user where the server text alone does not help. Call it with the result of
     * [classify] when [context] can give strings.
     */
    fun localized(context: Context, e: MailError): MailError = localized(e, context::getString)

    /** [localized] with the strings of [text]. */
    internal fun localized(e: MailError, text: (Int) -> String): MailError {
        if (e !is MailError.Setup) return e
        // The cause chain, not only the direct cause: coroutines can wrap an exception in a copy of itself.
        val causes = generateSequence(e.cause) { it.cause }.take(MAX_CAUSES)
        return when {
            causes.any { it is ImapNoException && NOT_CONNECTED.containsMatchIn(it.message.orEmpty()) } ->
                MailError.Setup(text(R.string.account_error_ms_imap_off), e.cause)
            causes.any { it is WrongHostException } -> MailError.Setup(text(R.string.account_error_sign_in_kind), e.cause)
            else -> e
        }
    }

    /** True when an [SmtpException] is in the cause chain of [t]. */
    internal fun hasSmtpCause(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.take(MAX_CAUSES).any { it is SmtpException }

    /**
     * Maps a refused IMAP AUTHENTICATE. A NO without a response code, or with an ALERT that is not a
     * throttle, refuses the credentials (RFC 3501 §6.2.2), e.g. Gmail "[ALERT] Application-specific
     * password required".
     */
    internal fun authFailure(e: ImapNoException): MailError {
        val error = classify(e, networkValidated = true)
        val code = e.code?.name
        return if (error is MailError.Server && (code == null || code in ALERT_CODES)) {
            MailError.AuthRequired(error.message.orEmpty(), cause = e)
        } else {
            error
        }
    }

    private fun isCertificateFailure(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.take(MAX_CAUSES).any {
            it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException
        }

    private fun isThrottle(code: String?, t: Throwable) = code in THROTTLE_CODES || isThrottleText(t.message.orEmpty())

    /**
     * Gmail's "bandwidth limits" and "too many simultaneous connections" refusals and Dovecot's connection
     * limit: a refusal without a throttle code is a throttle only by this text.
     */
    fun isThrottleText(text: String): Boolean = THROTTLE_TEXT.containsMatchIn(text)

    private fun text(t: Throwable): String = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    private const val MAX_CAUSES = 16
}

/**
 * A token of an issuer was not sent, because the server is not a host of that issuer (or the sign-in method does
 * not fit the account). [MailErrors.localized] gives the text for the user.
 */
internal class WrongHostException : java.io.IOException("Sign-in method not usable with this server")

/** [MailError.Setup] for a server that must not get a token; called before a token is read or refreshed. */
internal fun wrongHost(): MailError.Setup = MailError.Setup(WRONG_HOST_TEXT, WrongHostException())

/** The English text of [wrongHost] until [MailErrors.localized] replaces it. */
private const val WRONG_HOST_TEXT = "Plico cannot use this sign-in method with this server. Remove this account and add it again."

/** Runs one AUTHENTICATE / AUTH exchange; a refusal becomes [MailError.AuthRequired] or [MailError.Throttled]. */
internal inline fun <T> authenticating(block: () -> T): T = try {
    block()
} catch (e: ImapNoException) {
    throw MailErrors.authFailure(e)
} catch (e: SmtpException) {
    throw MailErrors.classify(e, networkValidated = true)
}
