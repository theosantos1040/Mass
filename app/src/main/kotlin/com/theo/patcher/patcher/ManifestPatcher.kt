// patcher/ManifestPatcher.kt — binary AndroidManifest.xml patcher
package com.theo.patcher.patcher

object ManifestPatcher {

    private val PERMISSIONS_TO_REMOVE = listOf(
        "com.android.vending.CHECK_LICENSE",
        "com.android.vending.BILLING",
        "com.google.android.c2dm.permission.RECEIVE"
    )

    fun patch(manifestBytes: ByteArray): ByteArray {
        val result = manifestBytes.copyOf()
        for (permission in PERMISSIONS_TO_REMOVE) {
            nullOutString(result, permission)
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
