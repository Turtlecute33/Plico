package app.plico.ui.compose

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.plico.PlicoApp
import app.plico.R
import app.plico.html.IncomingUris
import app.plico.html.Mailto
import app.plico.mail.mime.Address
import app.plico.mail.mime.HeaderSafety
import app.plico.sync.DraftPrefill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns a share or mailto intent from another app into a local draft (DESIGN §11.6, privacy PS2). */
object ComposeIntents {
    /** What an intent carries, read with typed getters. [mailto] is the data URI of SENDTO and VIEW. */
    internal data class Shared(
        val mailto: String? = null,
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val bcc: List<String> = emptyList(),
        val subject: String? = null,
        val text: String? = null,
    )

    /**
     * The new draft id, or null when the intent has nothing usable or no account exists. The draft belongs to
     * the first account. Files from `EXTRA_STREAM` and `ClipData` are copied at once, and only when
     * [IncomingUris.allowed]; a toast tells the user about files that were not attached.
     */
    suspend fun draftFromIntent(app: PlicoApp, intent: Intent): Long? {
        val (shared, streams) = read(intent) ?: return null
        val prefill = prefill(shared)
        var failed = 0
        val draftId = withContext(Dispatchers.IO) {
            val allowed = streams.filter { IncomingUris.allowed(app, it) }
            failed = streams.size - allowed.size
            val accountId = app.accounts.all().firstOrNull()?.id
            if (accountId == null || shared.mailto == null && isEmpty(prefill) && allowed.isEmpty()) {
                return@withContext null
            }
            app.mail.newDraft(accountId, prefill).also { draftId ->
                for (uri in allowed) {
                    try {
                        app.mail.addAttachment(draftId, uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed++
                    }
                }
            }
        }
        if (failed > 0) toast(app, failed)
        return draftId
    }

    /** The draft content for [shared]: valid addresses only, no control characters in the subject. */
    internal fun prefill(shared: Shared): DraftPrefill {
        val mailto = shared.mailto?.let(Mailto::parse)
        return DraftPrefill(
            to = recipients(mailto?.to.orEmpty() + addresses(shared.to)),
            cc = recipients(mailto?.cc.orEmpty() + addresses(shared.cc)),
            bcc = recipients(mailto?.bcc.orEmpty() + addresses(shared.bcc)),
            subject = HeaderSafety.clean(mailto?.subject?.ifEmpty { null } ?: shared.subject.orEmpty()),
            body = Mailto.cleanBody(mailto?.body?.ifEmpty { null } ?: shared.text.orEmpty()),
        )
    }

    internal fun isEmpty(prefill: DraftPrefill): Boolean = prefill == DraftPrefill()

    /**
     * SEND and SEND_MULTIPLE: extras and streams. SENDTO: the mailto URI plus the text extras (the documented
     * way to prefill). VIEW: the mailto URI only. Null for anything else or a URI that is not mailto.
     */
    internal fun read(intent: Intent): Pair<Shared, List<Uri>>? = when (intent.action) {
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> extras(intent, mailto = null) to streams(intent)
        Intent.ACTION_SENDTO -> mailtoOf(intent)?.let { extras(intent, it) to emptyList() }
        Intent.ACTION_VIEW -> mailtoOf(intent)?.let { Shared(mailto = it) to emptyList() }
        else -> null
    }

    private fun mailtoOf(intent: Intent): String? =
        safe { intent.dataString }?.takeIf { it.startsWith("mailto:", ignoreCase = true) }

    private fun extras(intent: Intent, mailto: String?) = Shared(
        mailto = mailto,
        to = strings(intent, Intent.EXTRA_EMAIL),
        cc = strings(intent, Intent.EXTRA_CC),
        bcc = strings(intent, Intent.EXTRA_BCC),
        subject = safe { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) }?.toString(),
        text = safe { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) }?.toString(),
    )

    /** The documented type is String[]; some apps send one String. */
    private fun strings(intent: Intent, name: String): List<String> =
        safe { intent.getStringArrayExtra(name) }?.filterNotNull()
            ?: safe { intent.getStringExtra(name) }?.let(::listOf)
            ?: emptyList()

    /** `EXTRA_STREAM` (one URI or a list, whatever the action) and the `ClipData` URIs, without duplicates. */
    private fun streams(intent: Intent): List<Uri> {
        val uris = LinkedHashSet<Uri>()
        safe { intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) }?.let(uris::add)
        safe { intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) }?.filterNotNullTo(uris)
        safe { intent.clipData }?.let { clip ->
            for (i in 0 until clip.itemCount) safe { clip.getItemAt(i).uri }?.let(uris::add)
        }
        return uris.toList()
    }

    /** A malformed extra (wrong type, bad parcel) must not crash the process that also runs push. */
    private inline fun <T> safe(read: () -> T?): T? = try {
        read()
    } catch (_: RuntimeException) {
        null
    }

    private fun addresses(values: List<String>): List<Address> = values.flatMap { Mailto.addresses(it) }

    private fun recipients(list: List<Address>): String =
        Recipients.format(Recipients.newOnes(emptyList(), list.map { Recipient.Valid(it) }))

    private suspend fun toast(app: PlicoApp, count: Int) = withContext(Dispatchers.Main) {
        val text = app.resources.getQuantityString(R.plurals.compose_share_failed, count, count)
        Toast.makeText(app, text, Toast.LENGTH_LONG).show()
    }
}
