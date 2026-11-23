package com.theo.patcher.patcher

object ProtectionPatcher {

    private val TAMPER_STRINGS = listOf(
        "luckypatcher",
        "lucky patcher",
        "com.forpda.lp",
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatch",
        "com.android.vendinc",
        "xposed",
        "de.robv.android.xposed",
        "com.saurik.substrate",
        "com.theo.patcher"
    )

    private val INTEGRITY_METHODS = listOf(
        "SafetyNet",
        "safetynet",
        "PlayIntegrity",
        "IntegrityManager",
        "requestIntegrityToken"
    )

    private val INSTALLER_METHODS = listOf(
        "getInstallerPackageName",
        "getInstallSourceInfo"
    )

    data class ProtectionPatchReport(
        val patchedMethods: List<String>,
        val bytesModified: Int
    )

    fun patch(dexBytes: ByteArray): Pair<ByteArray, ProtectionPatchReport> {
        val patched = dexBytes.copyOf()
        val patchedMethods = mutableListOf<String>()
        var bytesModified = 0

        for (tamperStr in TAMPER_STRINGS) {
            val offsets = findStringOffsets(patched, tamperStr)
            for (offset in offsets) {
                val replacement = "x".repeat(tamperStr.length)
                val repBytes = replacement.toByteArray(Charsets.UTF_8)
                for (j in repBytes.indices) {
                    if (offset + j < patched.size) patched[offset + j] = repBytes[j]
                }
                patchedMethods += "protection:tamper($tamperStr)@$offset"
                bytesModified += repBytes.size
            }
        }

        for (method in INTEGRITY_METHODS) {
            val offsets = findStringOffsets(patched, method)
            for (offset in offsets) {
                val codeOffset = findMethodCodeOffset(patched, offset)
                if (codeOffset > 0) {
                    val n = injectReturnVoid(patched, codeOffset)
                    if (n > 0) {
                        patchedMethods += "protection:integrity($method)@$offset"
                        bytesModified += n
                    }
                }
            }
        }

        for (method in INSTALLER_METHODS) {
            val offsets = findStringOffsets(patched, method)
            for (offset in offsets) {
                val n = neutralizeBranches(patched, offset, 96)
                if (n > 0) {
                    patchedMethods += "protection:installer($method)@$offset"
                    bytesModified += n
                }
            }
        }

        if (bytesModified > 0) DexUtil.updateIntegrity(patched)

        return Pair(patched, ProtectionPatchReport(patchedMethods, bytesModified))
    }

    private fun injectReturnVoid(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size || offset < 112) return 0
        data[offset] = 0x0E.toByte(); data[offset + 1] = 0x00
        return 2
    }

    private fun neutralizeBranches(data: ByteArray, offset: Int, window: Int): Int {
        val end = minOf(data.size, offset + window)
        var patched = 0
        for (i in offset until end - 3) {
            val op = data[i].toInt() and 0xFF
            if (op in 0x32..0x3D) {
                data[i] = 0x00; data[i + 1] = 0x00
                data[i + 2] = 0x00; data[i + 3] = 0x00
                patched += 4
            }
        }
        return patched
    }

    private fun findStringOffsets(data: ByteArray, needle: String): List<Int> {
        val nb = needle.toByteArray(Charsets.UTF_8)
        val result = mutableListOf<Int>()
        var i = 0
        outer@ while (i <= data.size - nb.size) {
            for (j in nb.indices) { if (data[i + j] != nb[j]) { i++; continue@outer } }
            result += i; i += nb.size
        }
        return result
    }

    private fun findMethodCodeOffset(data: ByteArray, stringOffset: Int): Int {
        val searchStart = maxOf(0, stringOffset - 4096)
        for (offset in stringOffset downTo searchStart) {
            val b = data[offset].toInt() and 0xFF
            if ((b == 0x0F || b == 0x11 || b == 0x0E) && offset + 1 < data.size) return offset
        }
        return -1
    }
}
