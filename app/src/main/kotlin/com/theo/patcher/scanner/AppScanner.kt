package com.theo.patcher.scanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.theo.patcher.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AppScanner {

    suspend fun listInstalledApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { isUserApp(it) }
            .mapNotNull { pkg ->
                try {
                    AppInfo(
                        packageName = pkg.packageName,
                        appName = pm.getApplicationLabel(pkg).toString(),
                        icon = pm.getApplicationIcon(pkg),
                        apkPath = pkg.sourceDir,
                        versionName = runCatching {
                            pm.getPackageInfo(pkg.packageName, 0).versionName ?: "?"
                        }.getOrDefault("?"),
                        apkSize = File(pkg.sourceDir).length(),
                        scanned = false
                    )
                } catch (_: Exception) { null }
            }
            .sortedBy { it.appName.lowercase() }
    }

    suspend fun deepScan(app: AppInfo): AppInfo = withContext(Dispatchers.IO) {
        val apkFile = File(app.apkPath)
        val purchases = IAPDetector.detect(apkFile)
        val ads = AdDetector.detect(apkFile)
        val license = LicenseDetector.detect(apkFile)
        val protections = ProtectionDetector.detect(apkFile)

        app.copy(
            iapStatus = if (purchases.isEmpty()) AppInfo.IAPStatus.NO_IAP else AppInfo.IAPStatus.HAS_IAP,
            adStatus = if (ads.isEmpty()) AppInfo.AdStatus.NO_ADS else AppInfo.AdStatus.HAS_ADS,
            licenseStatus = if (license == null) AppInfo.LicenseStatus.NO_LICENSE else AppInfo.LicenseStatus.HAS_LICENSE,
            protectionStatus = if (protections.isEmpty()) AppInfo.ProtectionStatus.NO_PROTECTION else AppInfo.ProtectionStatus.HAS_PROTECTION,
            detectedPurchases = purchases,
            detectedAds = ads,
            detectedLicense = license,
            detectedProtections = protections,
            scanned = true
        )
    }

    private fun isUserApp(info: ApplicationInfo): Boolean {
        if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return false
        return true
    }
}
