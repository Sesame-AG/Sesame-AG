package io.github.aoguai.sesameag.util

import android.content.Context
import android.content.pm.PackageManager
import io.github.aoguai.sesameag.BuildConfig
import java.security.MessageDigest
import java.util.Locale

/**
 * Confirms that the installed APK is signed by the public certificate configured for official CI.
 *
 * The expected digest is deliberately public. The private signing key remains in GitHub Secrets.
 */
object OfficialBuildVerifier {
    private val sha256FingerprintPattern = Regex("[0-9A-F]{64}")

    @Suppress("DEPRECATION")
    fun isOfficiallySigned(context: Context): Boolean {
        val expectedFingerprint = BuildConfig.OFFICIAL_SIGNING_CERT_SHA256
        if (!expectedFingerprint.matches(sha256FingerprintPattern)) {
            return false
        }

        return runCatching {
            val packageInfo =
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            packageInfo.signingInfo
                ?.apkContentsSigners
                ?.any { signature -> certificateSha256(signature.toByteArray()) == expectedFingerprint }
                ?: false
        }.getOrDefault(false)
    }

    private fun certificateSha256(certificateBytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(certificateBytes)
            .joinToString(separator = "") { byte ->
                "%02X".format(Locale.ROOT, byte.toInt() and 0xFF)
            }
}
