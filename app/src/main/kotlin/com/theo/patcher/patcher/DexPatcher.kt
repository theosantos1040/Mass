package com.theo.patcher.patcher

import com.theo.patcher.model.PurchaseInfo

object DexPatcher {

    private val TARGET_METHODS = listOf(
        "verifyPurchase",
        "verifyValidSignature",
        "verifyPurchaseSignature",
        "validatePurchase",
        "isValidPurchase",
        "checkPurchaseSignature"
    )

    private val RESPONSE_CODE_FIELD = "responseCode"
    private val OP_NOP = 0x00.toByte()

    data class PatchReport(
        val patchedMethods: List<String>,
        val modifiedOffsets: List<Int>,
        val bytesModified: Int
    )

    fun patch(dexBytes: ByteArray, purchases: List<PurchaseInfo>): Pair<ByteArray, PatchReport> {
        val patched = dexBytes.copyOf()
        val patchedMethods = mutableListOf<String>()
        val modifiedOffsets = mutableListOf<Int>()
        var bytesModified = 0

        for (method in TARGET_METHODS) {
            val offsets = findStringOffsets(patched, method)
            for (offset in offsets) {
                val codeOffset = findMethodCodeOffset(patched, offset)
                if (codeOffset > 0) {
                    val n = injectReturnTrue(patched, codeOffset)
                    if (n > 0) {
                        patchedMethods += method
                        modifiedOffsets += codeOffset
                        bytesModified += n
                    }
                }
            }
        }

        val billingOffsets = findStringOffsets(patched, RESPONSE_CODE_FIELD)
        for (offset in billingOffsets) {
            val n = patchBillingResponseCheck(patched, offset)
            if (n > 0) {
                patchedMethods += "BillingResponseCode.check@$offset"
                modifiedOffsets += offset
                bytesModified += n
            }
        }

        val stateOffsets = findStringOffsets(patched, "getPurchaseState")
        for (offset in stateOffsets) {
            val codeOffset = findMethodCodeOffset(patched, offset)
            if (codeOffset > 0) {
                val n = injectReturnOne(patched, codeOffset)
                if (n > 0) {
                    patchedMethods += "getPurchaseState@$codeOffset"
                    modifiedOffsets += codeOffset
                    bytesModified += n
                }
            }
        }

        val ackOffsets = findStringOffsets(patched, "isAcknowledged")
        for (offset in ackOffsets) {
            val codeOffset = findMethodCodeOffset(patched, offset)
            if (codeOffset > 0) {
                val n = injectReturnTrue(patched, codeOffset)
                if (n > 0) {
                    patchedMethods += "isAcknowledged@$codeOffset"
                    modifiedOffsets += codeOffset
                    bytesModified += n
                }
            }
        }

        val jsonOffsets = findStringOffsets(patched, "getOriginalJson")
        for (offset in jsonOffsets) {
            val n = neutralizeNearbyBranches(patched, offset, 128)
            if (n > 0) {
                patchedMethods += "getOriginalJson.branch@$offset"
                modifiedOffsets += offset
                bytesModified += n
            }
        }

        if (bytesModified > 0) DexUtil.updateIntegrity(patched)

        return Pair(patched, PatchReport(patchedMethods, modifiedOffsets, bytesModified))
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

    private fun injectReturnTrue(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        if (offset < 112) return 0
        data[offset]     = 0x12
        data[offset + 1] = 0x10
        data[offset + 2] = 0x0F
        data[offset + 3] = 0x00
        return 4
    }

    private fun injectReturnOne(data: ByteArray, offset: Int): Int = injectReturnTrue(data, offset)

    private fun patchBillingResponseCheck(data: ByteArray, offset: Int): Int {
        val window = 64
        val end = minOf(data.size, offset + window)
        var patched = 0
        for (i in offset until end - 1) {
            val op = data[i].toInt() and 0xFF
            if (op == 0x38 || op == 0x39) {
                data[i]     = OP_NOP
                data[i + 1] = OP_NOP
                if (i + 3 < data.size) {
                    data[i + 2] = OP_NOP
                    data[i + 3] = OP_NOP
                }
                patched += 4
            }
        }
        return patched
    }

    private fun neutralizeNearbyBranches(data: ByteArray, offset: Int, window: Int): Int {
        val end = minOf(data.size, offset + window)
        var patched = 0
        for (i in offset until end - 3) {
            val op = data[i].toInt() and 0xFF
            if (op in listOf(0x32, 0x33, 0x38, 0x39)) {
                data[i]     = OP_NOP
                data[i + 1] = OP_NOP
                data[i + 2] = OP_NOP
                data[i + 3] = OP_NOP
                patched += 4
            }
        }
        return patched
    }
}
