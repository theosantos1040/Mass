package com.theo.patcher.patcher

object AdPatcher {

    private val AD_LOAD_METHODS = listOf(
        "loadAd",
        "loadInterstitial",
        "loadRewardedAd",
        "loadBanner",
        "requestAd",
        "fetchAd",
        "loadBannerAd",
        "loadNativeAd",
        "showAd",
        "showInterstitial",
        "showRewardedVideo",
        "displayAd",
        "presentAd"
    )

    private val AD_CHECK_METHODS = listOf(
        "isAdAvailable",
        "isLoaded",
        "isAdLoaded",
        "isReady",
        "canShow"
    )

    private val AD_CONTEXT_STRINGS = listOf(
        "com.google.android.gms.ads",
        "com.facebook.ads",
        "com.unity3d.ads",
        "com.applovin",
        "com.chartboost",
        "com.ironsource",
        "com.vungle",
        "AdActivity",
        "InterstitialAd",
        "RewardedAd",
        "AdView",
        "AdRequest",
        "MobileAds"
    )

    data class AdPatchReport(
        val patchedMethods: List<String>,
        val bytesModified: Int
    )

    fun patch(dexBytes: ByteArray): Pair<ByteArray, AdPatchReport> {
        val patched = dexBytes.copyOf()
        val patchedMethods = mutableListOf<String>()
        var bytesModified = 0

        for (method in AD_LOAD_METHODS) {
            val offsets = findStringOffsets(patched, method)
            for (offset in offsets) {
                if (isNearAdContext(patched, offset)) {
                    val codeOffset = findMethodCodeOffset(patched, offset)
                    if (codeOffset > 0) {
                        val n = injectReturnVoid(patched, codeOffset)
                        if (n > 0) {
                            patchedMethods += "ad:$method@$offset"
                            bytesModified += n
                        }
                    }
                }
            }
        }

        for (method in AD_CHECK_METHODS) {
            val offsets = findStringOffsets(patched, method)
            for (offset in offsets) {
                if (isNearAdContext(patched, offset)) {
                    val codeOffset = findMethodCodeOffset(patched, offset)
                    if (codeOffset > 0) {
                        val n = injectReturnFalse(patched, codeOffset)
                        if (n > 0) {
                            patchedMethods += "ad:$method@$offset"
                            bytesModified += n
                        }
                    }
                }
            }
        }

        if (bytesModified > 0) DexUtil.updateIntegrity(patched)

        return Pair(patched, AdPatchReport(patchedMethods, bytesModified))
    }

    private fun isNearAdContext(data: ByteArray, offset: Int): Boolean {
        val windowStart = maxOf(0, offset - 512)
        val windowEnd = minOf(data.size, offset + 512)
        val window = String(data, windowStart, windowEnd - windowStart, Charsets.ISO_8859_1)
        return AD_CONTEXT_STRINGS.any { window.contains(it, ignoreCase = true) }
    }

    private fun injectReturnVoid(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size || offset < 112) return 0
        data[offset] = 0x0E.toByte()
        data[offset + 1] = 0x00
        return 2
    }

    private fun injectReturnFalse(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size || offset < 112) return 0
        data[offset]     = 0x12
        data[offset + 1] = 0x00
        data[offset + 2] = 0x0F
        data[offset + 3] = 0x00
        return 4
    }

    private fun findStringOffsets(data: ByteArray, needle: String): List<Int> {
        val nb = needle.toByteArray(Charsets.UTF_8)
        val result = mutableListOf<Int>()
        var i = 0
        outer@ while (i <= data.size - nb.size) {
            for (j in nb.indices) {
                if (data[i + j] != nb[j]) { i++; continue@outer }
            }
            result += i
            i += nb.size
        }
        return result
    }

    private fun findMethodCodeOffset(data: ByteArray, stringOffset: Int): Int {
        val searchStart = maxOf(0, stringOffset - 4096)
        for (offset in stringOffset downTo searchStart) {
            val b = data[offset].toInt() and 0xFF
            if ((b == 0x0F || b == 0x11 || b == 0x0E) && offset + 1 < data.size) {
                return offset
            }
        }
        return -1
    }
}
