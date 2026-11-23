// model/AppInfo.kt — Android, Kotlin, com.theo.patcher
package com.theo.patcher.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val apkPath: String,
    val versionName: String,
    val apkSize: Long,
    var iapStatus: IAPStatus = IAPStatus.UNKNOWN,
    var detectedPurchases: List<PurchaseInfo> = emptyList()
) {
    enum class IAPStatus {
        UNKNOWN,
        NO_IAP,
        HAS_IAP,
        PATCHED
    }
}
