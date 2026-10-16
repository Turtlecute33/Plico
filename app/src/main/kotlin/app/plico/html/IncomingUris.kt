package app.plico.html

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri

/** Which URIs from share intents Compose may read (DESIGN.md §11.6, privacy PS2). */
object IncomingUris {
    private val authorityChars = Regex("[A-Za-z0-9._-]+")

    /**
     * True when a URI from `EXTRA_STREAM` or `ClipData` may be copied into a draft: only `content://`, with a
     * plain authority (no `user@` part, which the content resolver strips before it finds the provider), whose
     * provider is not Plico's own. `file://` and every other scheme are rejected.
     *
     * @param authority `uri.authority` (decoded, as the content resolver reads it).
     * @param ownPackage `context.packageName`.
     * @param providerPackage the package of the provider of [authority], null when none is found.
     */
    fun allowed(scheme: String?, authority: String?, ownPackage: String, providerPackage: String?): Boolean =
        scheme.equals("content", ignoreCase = true) &&
            authority != null && authorityChars.matches(authority) &&
            providerPackage != ownPackage

    /** [allowed] for [uri], with the provider looked up in the package manager. */
    fun allowed(context: Context, uri: Uri): Boolean {
        val authority = uri.authority
        val provider = authority?.let {
            context.packageManager.resolveContentProvider(it, PackageManager.ComponentInfoFlags.of(0))?.packageName
        }
        return allowed(uri.scheme, authority, context.packageName, provider)
    }
}
