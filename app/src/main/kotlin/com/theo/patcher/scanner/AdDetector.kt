package com.theo.patcher.scanner

import com.theo.patcher.model.AdInfo
import java.io.File
import java.util.zip.ZipFile

object AdDetector {

    private val AD_SDKS = mapOf(
        "AdMob" to listOf(
            "com/google/android/gms/ads",
            "com/google/android/gms/internal/ads",
            "com.google.android.gms.ads.AdView",
            "com.google.android.gms.ads.InterstitialAd",
            "com.google.android.gms.ads.rewarded"
        ),
        "Facebook Ads" to listOf(
            "com/facebook/ads",
            "com.facebook.ads.AdView",
            "com.facebook.ads.InterstitialAd"
        ),
        "Unity Ads" to listOf(
            "com/unity3d/services/ads",
            "com/unity3d/ads",
            "com.unity3d.ads.UnityAds"
        ),
        "AppLovin" to listOf(
            "com/applovin/sdk",
            "com/applovin/mediation",
            "com.applovin.sdk.AppLovinSdk"
        ),
        "IronSource" to listOf(
            "com/ironsource/mediationsdk",
            "com/ironsource/sdk",
            "com.ironsource.mediationsdk.IronSource"
        ),
        "Chartboost" to listOf(
            "com/chartboost/sdk",
            "com.chartboost.sdk.Chartboost"
        ),
        "Vungle" to listOf(
            "com/vungle/warren",
            "com/vungle/ads",
            "com.vungle.warren.Vungle"
        ),
        "StartApp" to listOf(
            "com/startapp/sdk",
            "com.startapp.sdk.adsbase"
        ),
        "InMobi" to listOf(
            "com/inmobi/ads",
            "com.inmobi.ads.InMobiAdActivity"
        ),
        "MoPub" to listOf(
            "com/mopub/mobileads",
            "com.mopub.mobileads.MoPubView"
        ),
        "AdColony" to listOf(
            "com/adcolony/sdk",
            "com.adcolony.sdk.AdColony"
        )
    )

    fun detect(apkFile: File): List<AdInfo> {
        val results = mutableListOf<AdInfo>()
        try {
            ZipFile(apkFile).use { zip ->
                val allText = StringBuilder()
                zip.entries().asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .forEach { entry ->
                        val dexBytes = zip.getInputStream(entry).readBytes()
                        allText.append(extractStrings(dexBytes))
                    }
                val text = allText.toString()

                for ((sdkName, patterns) in AD_SDKS) {
                    val found = patterns.filter { text.contains(it) }
                    if (found.isNotEmpty()) {
                        results += AdInfo(
                            sdkName = sdkName,
                            sdkPackage = patterns.first(),
                            components = found
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun extractStrings(dex: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < dex.size) {
            val b = dex[i].toInt() and 0xFF
            if (b in 32..126) {
                val word = StringBuilder()
                while (i < dex.size && (dex[i].toInt() and 0xFF) in 32..126) {
                    word.append(dex[i].toInt().toChar())
                    i++
                }
                if (word.length > 5) sb.append(word).append('\n')
            } else {
                i++
            }
        }
        return sb.toString()
    }
}
