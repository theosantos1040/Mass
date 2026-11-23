package com.theo.patcher.patcher

object LicensePatcher {

    private val RETURN_TRUE_METHODS = listOf(
        "checkAccess", "allowAccess", "isLicensed", "validateLicense",
        "verifyLicense", "isActivated", "isPurchased", "isRegistered", "licenseValid"
    )

    private val RETURN_FALSE_METHODS = listOf(
        "isTrialExpired"
    )

    private val NOP_METHODS = listOf(
        "dontAllow", "applicationError"
    )

    data class LicensePatchReport(
        val patchedMethods: List<String>,
        val bytesModified: Int
    )

    fun patch(dexBytes: ByteArray): Pair<ByteArray, LicensePatchReport> {
        val patched = dexBytes.copyOf()
        val patchedMethods = mutableListOf<String>()
        var bytesModified = 0

        val allMethods = RETURN_TRUE_METHODS + RETURN_FALSE_METHODS + NOP_METHODS

        for (method in allMethods) {
            val offsets = findStringOffsets(patched, method)
            for (offset in offsets) {
                val codeOffset = findMethodCodeOffset(patched, offset)
                if (codeOffset > 0) {
                    val n = when {
                        method in RETURN_TRUE_METHODS -> injectReturnTrue(patched, codeOffset)
                        method in RETURN_FALSE_METHODS -> injectReturnFalse(patched, codeOffset)
                        method in NOP_METHODS -> injectReturnVoid(patched, codeOffset)
                        else -> 0
                    }
                    if (n > 0) {
                        patchedMethods += "license:$method@$offset"
                        bytesModified += n
                    }
                }
            }
        }

        val lvlOffsets = findStringOffsets(patched, "NOT_LICENSED")
        for (offset in lvlOffsets) {
            val window = minOf(patched.size, offset + 64)
            for (i in offset until window - 1) {
                val op = patched[i].toInt() and 0xFF
                if (op == 0x38 || op == 0x39) {
                    patched[i] = 0x00; patched[i + 1] = 0x00
                    if (i + 3 < patched.size) { patched[i + 2] = 0x00; patched[i + 3] = 0x00 }
                    bytesModified += 4
                    patchedMethods += "license:NOT_LICENSED.branch@$i"
                }
            }
        }

        if (bytesModified > 0) updateDexChecksum(patched)

        return Pair(patched, LicensePatchReport(patchedMethods, bytesModified))
    }

    private fun injectReturnTrue(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size || offset < 112) return 0
        data[offset] = 0x12; data[offset + 1] = 0x10
        data[offset + 2] = 0x0F; data[offset + 3] = 0x00
        return 4
    }

    private fun injectReturnFalse(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size || offset < 112) return 0
        data[offset] = 0x12; data[offset + 1] = 0x00
        data[offset + 2] = 0x0F; data[offset + 3] = 0x00
        return 4
    }

    private fun injectReturnVoid(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size || offset < 112) return 0
        data[offset] = 0x0E.toByte(); data[offset + 1] = 0x00
        return 2
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

    private fun updateDexChecksum(dex: ByteArray) {
        if (dex.size < 112) return
        var s1 = 1L; var s2 = 0L
        for (i in 12 until dex.size) {
            s1 = (s1 + (dex[i].toLong() and 0xFF)) % 65521
            s2 = (s2 + s1) % 65521
        }
        val checksum = ((s2 shl 16) or s1).toInt()
        dex[8] = (checksum and 0xFF).toByte()
        dex[9] = ((checksum shr 8) and 0xFF).toByte()
        dex[10] = ((checksum shr 16) and 0xFF).toByte()
        dex[11] = ((checksum shr 24) and 0xFF).toByte()
    }
}
