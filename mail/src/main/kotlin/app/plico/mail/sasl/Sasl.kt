package app.plico.mail.sasl

import java.util.Base64

/** SASL initial responses, base64-encoded. */
object Sasl {
    /** RFC 4616 PLAIN: authzid empty, authcid, password. */
    fun plain(user: String, password: String): String =
        encode("\u0000$user\u0000$password")

    /** Google XOAUTH2: "user=" email ^A "auth=Bearer " token ^A ^A. */
    fun xoauth2(user: String, accessToken: String): String =
        encode("user=$user\u0001auth=Bearer $accessToken\u0001\u0001")

    private fun encode(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
}
