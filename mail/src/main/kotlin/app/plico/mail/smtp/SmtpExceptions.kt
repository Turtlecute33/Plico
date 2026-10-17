package app.plico.mail.smtp

import java.io.IOException

/**
 * A negative server reply. [code] is the reply code, [enhanced] the RFC 3463 code (`5.7.8`) when the server
 * sent one, and the message is the reply as received (it can name addresses, so do not log it).
 */
class SmtpException(val code: Int, val enhanced: String?, message: String, val stage: Stage) : IOException(message) {
    enum class Stage { CONNECT, EHLO, STARTTLS, AUTH, MAIL, RCPT, DATA, FINAL }

    /** 4xx at any stage; at RCPT it is the first 4xx reply. 5xx at RCPT is [SmtpRecipientException]. */
    val transient: Boolean get() = code in 400..499
}

/**
 * At least one RCPT TO got a 5xx reply, or a 4xx with an address error code (4.1.x); the client sent RSET
 * and no DATA.
 * Keys are those recipients, values the server replies.
 */
class SmtpRecipientException(val rejected: Map<String, String>) :
    IOException("Recipients rejected: ${rejected.size}")

/** The message was uploaded but the reply to the final dot did not arrive. It may have been sent. */
class SmtpUncertainException(cause: Throwable) : IOException("No reply after the message was sent", cause)

/** The server offers no AUTH mechanism that the client can use for a password (PLAIN or LOGIN). */
class SmtpAuthUnavailableException : IOException("No usable AUTH mechanism")
