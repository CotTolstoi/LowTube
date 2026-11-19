package com.lowtube.android.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

/** Returns non-secret local OAuth setup facts without reading account data or authorization tokens. */
object GoogleAuthorizationDiagnostics {
    data class Details(
        val provider: String,
        val providerVersion: String,
        val packageName: String,
        val signingSha1: String,
    )

    fun collect(context: Context): Details {
        val appContext = context.applicationContext
        val providerInfo = runCatching {
            appContext.packageManager.getPackageInfo(GOOGLE_SERVICES_PACKAGE, 0)
        }.getOrNull()
        val providerLabel = providerInfo?.applicationInfo?.let { appInfo ->
            runCatching { appContext.packageManager.getApplicationLabel(appInfo).toString() }.getOrNull()
        }.orEmpty()
        val providerName = when {
            providerInfo == null -> "Unavailable"
            providerLabel.contains("microg", ignoreCase = true) -> "microG"
            providerLabel.isNotBlank() -> providerLabel.take(MAX_LABEL_LENGTH)
            else -> "Google services"
        }
        val providerVersion = providerInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toString()
            else @Suppress("DEPRECATION") it.versionCode.toString()
        }.orEmpty()

        return Details(
            provider = providerName,
            providerVersion = providerVersion,
            packageName = appContext.packageName,
            signingSha1 = appSigningSha1(appContext),
        )
    }

    private fun appSigningSha1(context: Context): String {
        val signatures = runCatching {
            val packageManager = context.packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
        }.getOrNull().orEmpty()

        val certificate = signatures.firstOrNull()?.toByteArray() ?: return "unavailable"
        val digest = runCatching { MessageDigest.getInstance("SHA-1").digest(certificate) }.getOrNull()
            ?: return "unavailable"
        return digest.joinToString(":") { byte -> "%02X".format(Locale.ROOT, byte.toInt() and 0xFF) }
    }

    private const val GOOGLE_SERVICES_PACKAGE = "com.google.android.gms"
    private const val MAX_LABEL_LENGTH = 64
}
