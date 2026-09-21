package com.theo.patcher.scanner

import com.theo.patcher.model.LicenseInfo
import java.io.File
import java.util.zip.ZipFile

object LicenseDetector {

    private val LVL_PATTERNS = mapOf(
        "checkAccess" to LicenseInfo.LicenseType.GOOGLE_LVL,
        "LicenseChecker" to LicenseInfo.LicenseType.GOOGLE_LVL,
        "LicenseCheckerCallback" to LicenseInfo.LicenseType.GOOGLE_LVL,
        "ServerManagedPolicy" to LicenseInfo.LicenseType.SERVER_MANAGED,
        "StrictPolicy" to LicenseInfo.LicenseType.STRICT_POLICY,
        "APKExpansionPolicy" to LicenseInfo.LicenseType.SERVER_MANAGED,
        "com/google/android/vending/licensing" to LicenseInfo.LicenseType.GOOGLE_LVL,
        "allowAccess" to LicenseInfo.LicenseType.GOOGLE_LVL,
        "dontAllow" to LicenseInfo.LicenseType.GOOGLE_LVL
    )

    private val CUSTOM_LICENSE_PATTERNS = listOf(
        "isLicensed",
        "checkLicense",
        "validateLicense",
        "verifyLicense",
        "isActivated",
        "isPurchased",
        "isRegistered",
        "isTrialExpired",
        "licenseValid"
    )

    fun detect(apkFile: File): LicenseInfo? {
        try {
            ZipFile(apkFile).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .forEach { entry ->
                        val dexBytes = zip.getInputStream(entry).readBytes()
                        val text = extractStrings(dexBytes)

                        for ((pattern, type) in LVL_PATTERNS) {
                            if (text.contains(pattern)) {
                                return LicenseInfo(
                                    type = type,
                                    className = extractClassName(text, pattern),
                                    methodName = pattern,
                                    offset = findOffset(dexBytes, pattern)
                                )
                            }
                        }

                        for (pattern in CUSTOM_LICENSE_PATTERNS) {
                            if (text.contains(pattern)) {
                                return LicenseInfo(
                                    type = LicenseInfo.LicenseType.CUSTOM,
                                    className = extractClassName(text, pattern),
                                    methodName = pattern,
                                    offset = findOffset(dexBytes, pattern)
                                )
                            }
                        }
                    }
            }
        } catch (_: Exception) {}
        return null
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
                if (word.length > 3) sb.append(word).append('\n')
            } else {
                i++
            }
        }
        return sb.toString()
    }

    private fun extractClassName(text: String, method: String): String {
        val lines = text.lines()
        val idx = lines.indexOfFirst { it.contains(method) }
        if (idx > 0) {
            for (i in idx - 1 downTo maxOf(0, idx - 5)) {
                val line = lines[i].trim()
                if (line.contains("/") && line.contains(";")) return line
            }
        }
        return "unknown"
    }

    private fun findOffset(dex: ByteArray, needle: String): Int {
        val nb = needle.toByteArray()
        for (i in 0..dex.size - nb.size) {
            var match = true
            for (j in nb.indices) {
                if (dex[i + j] != nb[j]) { match = false; break }
            }
            if (match) return i
        }
        return -1
    }
}
