package com.theo.patcher.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val apkPath: String,
    val splitApkPaths: List<String> = emptyList(),
    val versionName: String,
    val apkSize: Long,
    var iapStatus: IAPStatus = IAPStatus.UNKNOWN,
    var adStatus: AdStatus = AdStatus.UNKNOWN,
    var licenseStatus: LicenseStatus = LicenseStatus.UNKNOWN,
    var protectionStatus: ProtectionStatus = ProtectionStatus.UNKNOWN,
    var detectedPurchases: List<PurchaseInfo> = emptyList(),
    var detectedAds: List<AdInfo> = emptyList(),
    var detectedLicense: LicenseInfo? = null,
    var detectedProtections: List<ProtectionInfo> = emptyList(),
    var scanned: Boolean = false
) {
    enum class IAPStatus { UNKNOWN, NO_IAP, HAS_IAP, PATCHED }
    enum class AdStatus { UNKNOWN, NO_ADS, HAS_ADS, PATCHED }
    enum class LicenseStatus { UNKNOWN, NO_LICENSE, HAS_LICENSE, PATCHED }
    enum class ProtectionStatus { UNKNOWN, NO_PROTECTION, HAS_PROTECTION, PATCHED }
}
