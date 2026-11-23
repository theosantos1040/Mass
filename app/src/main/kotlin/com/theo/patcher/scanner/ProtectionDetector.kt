package com.theo.patcher.scanner

import com.theo.patcher.model.ProtectionInfo
import java.io.File
import java.util.zip.ZipFile

object ProtectionDetector {

    private val SIGNATURE_CHECK_PATTERNS = listOf(
        "checkSignature",
        "verifySignature",
        "getSigningCertificate"
    )

    private val INSTALLER_CHECK_PATTERNS = listOf(
        "getInstallerPackageName",
        "getInstallSourceInfo"
    )

    private val INTEGRITY_CHECK_PATTERNS = listOf(
        "SafetyNet",
        "safetynet",
        "PlayIntegrity",
        "play.integrity",
        "com/google/android/gms/safetynet",
        "com/google/android/play/core/integrity",
        "IntegrityManager",
        "IntegrityTokenRequest"
    )

    private val DEBUG_CHECK_PATTERNS = listOf(
        "isDebuggable",
        "Debug.isDebuggerConnected",
        "FLAG_DEBUGGABLE"
    )

    private val ROOT_CHECK_PATTERNS = listOf(
        "/system/app/Superuser",
        "/system/xbin/su",
        "com.noshufou.android.su",
        "eu.chainfire.supersu",
        "com.topjohnwu.magisk",
        "RootBeer",
        "isDeviceRooted"
    )

    private val TAMPER_CHECK_PATTERNS = listOf(
        "luckypatcher",
        "xposed",
        "de.robv.android.xposed",
        "com.saurik.substrate",
        "com.forpda.lp",
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatch",
        "com.android.vendinc"
    )

    fun detect(apkFile: File): List<ProtectionInfo> {
        val results = mutableListOf<ProtectionInfo>()
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

                checkPatterns(text, SIGNATURE_CHECK_PATTERNS, ProtectionInfo.ProtectionType.SIGNATURE_CHECK, results)
                checkPatterns(text, INSTALLER_CHECK_PATTERNS, ProtectionInfo.ProtectionType.INSTALLER_CHECK, results)
                checkPatterns(text, INTEGRITY_CHECK_PATTERNS, ProtectionInfo.ProtectionType.INTEGRITY_CHECK, results)
                checkPatterns(text, DEBUG_CHECK_PATTERNS, ProtectionInfo.ProtectionType.DEBUG_CHECK, results)
                checkPatterns(text, ROOT_CHECK_PATTERNS, ProtectionInfo.ProtectionType.ROOT_CHECK, results)
                checkPatterns(text, TAMPER_CHECK_PATTERNS, ProtectionInfo.ProtectionType.TAMPER_CHECK, results)
            }
        } catch (_: Exception) {}
        return results
    }

    private fun checkPatterns(
        text: String,
        patterns: List<String>,
        type: ProtectionInfo.ProtectionType,
        results: MutableList<ProtectionInfo>
    ) {
        val found = patterns.filter { text.contains(it, ignoreCase = true) }
        if (found.isNotEmpty()) {
            results += ProtectionInfo(
                type = type,
                className = "detected",
                detail = found.joinToString(", ")
            )
        }
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
}
