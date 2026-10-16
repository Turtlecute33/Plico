package app.plico.mail.imap

import java.io.IOException

open class ImapException(message: String) : IOException(message)

/** Tagged NO. */
class ImapNoException(val code: RespCode?, text: String) : ImapException(text)

/** Tagged BAD. */
class ImapBadException(text: String) : ImapException(text)

/** Untagged BYE: the server closes the connection. */
class ImapByeException(val code: RespCode?, text: String) : ImapException(text)

/** The server sent something that this client cannot parse, or broke a size limit. */
class ImapProtocolException(message: String) : ImapException(message)

class ImapMissingCapabilityException(val missing: Set<String>) :
    ImapException("Server lacks " + missing.sorted().joinToString(" "))
