package com.theo.patcher.patcher

object ManifestPatcher {

    private val AD_COMPONENTS_TO_REMOVE = listOf(
        "AdActivity",
        "com.google.android.gms.ads",
        "AudienceNetworkActivity",
        "com.facebook.ads",
        "UnityAdsFullscreenActivity"
    )

    fun patch(manifestBytes: ByteArray, removeAds: Boolean = true, removeLicense: Boolean = true): ByteArray {
        val result = manifestBytes.copyOf()

        if (removeLicense) {
            nullOutString(result, "com.android.vending.CHECK_LICENSE")
            nullOutString(result, "com.android.vending.BILLING")
            nullOutString(result, "com.google.android.c2dm.permission.RECEIVE")
        }

        if (removeAds) {
            nullOutString(result, "com.google.android.gms.permission.AD_ID")
            for (component in AD_COMPONENTS_TO_REMOVE) {
                nullOutString(result, component)
            }
        }

        return result
    }

    private fun nullOutString(data: ByteArray, str: String) {
        val utf16 = str.toByteArray(Charsets.UTF_16LE)
        replaceAllOccurrences(data, utf16, ByteArray(utf16.size) { 0x20 })
        val utf8 = str.toByteArray(Charsets.UTF_8)
        replaceAllOccurrences(data, utf8, ByteArray(utf8.size) { 0x20 })
    }

    private fun replaceAllOccurrences(data: ByteArray, needle: ByteArray, replacement: ByteArray) {
        var i = 0
        outer@ while (i <= data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) { i++; continue@outer }
            }
            replacement.copyInto(data, i)
            i += needle.size
        }
    }
}
