package app.plico.ui.setup

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

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
