package app.plico.ui.setup

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.security.MessageDigest

/** Opens [url] in the user's browser; false when no browser exists. */
@SuppressLint("UseKtx") // androidx.core is for FileProvider only (DESIGN §2).
internal fun openLink(context: Context, url: String): Boolean =
    start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))

/** Starts a system screen; false when none handles [intent]. */
internal fun start(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** SHA-1 fingerprint of the certificate that signed this build, for the Android OAuth client (DESIGN §6). */
internal fun signingSha1(context: Context): String? {
    val info = try {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    val certificate = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
    return fingerprint(MessageDigest.getInstance("SHA-1").digest(certificate.toByteArray()))
}

/** "AB:CD:…" as the Google Cloud console shows it. */
internal fun fingerprint(digest: ByteArray): String = digest.joinToString(":") { "%02X".format(it) }
