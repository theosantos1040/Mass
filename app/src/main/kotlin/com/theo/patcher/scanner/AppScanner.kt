// scanner/AppScanner.kt — lists installed user apps and runs IAP detection on each
package com.theo.patcher.scanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.theo.patcher.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AppScanner {

    suspend fun scanInstalledApps(
        context: Context,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { isUserApp(it) }

        packages.mapIndexedNotNull { index, pkg ->
            onProgress(index + 1, packages.size)
            try {
                val apkFile = File(pkg.sourceDir)
                val purchases = IAPDetector.detect(apkFile)
                val iapStatus = when {
                    purchases.isEmpty() -> AppInfo.IAPStatus.NO_IAP
                    else -> AppInfo.IAPStatus.HAS_IAP
                }

                AppInfo(
                    packageName = pkg.packageName,
                    appName = pm.getApplicationLabel(pkg).toString(),
                    icon = pm.getApplicationIcon(pkg),
                    apkPath = pkg.sourceDir,
                    versionName = runCatching {
                        pm.getPackageInfo(pkg.packageName, 0).versionName ?: "?"
                    }.getOrDefault("?"),
                    apkSize = apkFile.length(),
                    iapStatus = iapStatus,
                    detectedPurchases = purchases
                )
            } catch (e: Exception) {
                null
            }
        }.sortedWith(compareByDescending<AppInfo> {
            it.iapStatus == AppInfo.IAPStatus.HAS_IAP
        }.thenBy { it.appName })
    }

    private fun isUserApp(info: ApplicationInfo): Boolean {
        if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return false
        if (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) return true
        return true
    }
}
