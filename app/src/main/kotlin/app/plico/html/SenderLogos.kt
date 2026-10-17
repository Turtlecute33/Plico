package app.plico.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.plico.PlicoApp
import app.plico.app
import app.plico.data.Prefs

/**
 * Sender logos, only when the user turned them on (DESIGN.md §12). Only the domain of a sender is ever sent.
 * No logo source is wired yet: [cached] and [load] return null, [clear] and [prune] do nothing.
 */
class SenderLogos(private val app: PlicoApp) {
    /** The logo of the domain of [email] from memory, without I/O; null when it is not in memory. */
    fun cached(email: String): ImageBitmap? = null

    /**
     * The logo of the domain of [email] from the cache or from [source]. [before] and [exceptId] find an earlier
     * message from the same address (the first-contact rule of the website source). Null when there is none.
     */
    suspend fun load(email: String, source: Prefs.LogoSource, before: Long, exceptId: Long): ImageBitmap? = null

    /** Deletes every cached logo (memory and disk). Call it off the main thread. */
    fun clear() {}

    /**
     * At app start: deletes expired logo files, or all of them when logos are off ([enabled] false) or there is
     * no account. Call it off the main thread.
     */
    fun prune(enabled: Boolean, anyAccount: Boolean) {}
}

/**
 * The logo for a message row: the cached logo at once, then the loaded one. Null at once when logos are off
 * ([source] null), when the row may not show a logo ([allowed] false) or without a sender address.
 */
@Composable
fun rememberSenderLogo(
    email: String?,
    source: Prefs.LogoSource?,
    allowed: Boolean,
    before: Long,
    exceptId: Long,
): ImageBitmap? {
    if (source == null || !allowed || email == null) return null
    val logos = LocalContext.current.app.logos
    return produceState(logos.cached(email), email, source, before, exceptId) {
        logos.load(email, source, before, exceptId)?.let { value = it }
    }.value
}
