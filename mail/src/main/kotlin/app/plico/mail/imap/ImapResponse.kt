package app.plico.mail.imap

/** Response code in brackets, e.g. `[UIDVALIDITY 3857529045]`. [name] is upper-case. */
data class RespCode(val name: String, val args: List<ImapValue>)

sealed interface ImapResponse

/** `A1 OK [code] text`. [status] is OK, NO or BAD. */
data class Tagged(val tag: String, val status: String, val code: RespCode?, val text: String) : ImapResponse

/**
 * `* 23 EXISTS`, `* CAPABILITY ...`, `* OK [code] text`, `* 3 FETCH (...)`.
 * [keyword] is upper-case. For status keywords (OK NO BAD BYE PREAUTH) [code] and [text] are set and
 * [values] is empty; for the others [values] holds the tokens after the keyword.
 */
data class Untagged(
    val number: Long?,
    val keyword: String,
    val values: List<ImapValue>,
    val code: RespCode?,
    val text: String?,
) : ImapResponse

/** `+ text` */
data class Continuation(val text: String) : ImapResponse
