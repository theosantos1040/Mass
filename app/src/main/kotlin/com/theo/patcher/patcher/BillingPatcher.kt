package com.theo.patcher.patcher

object BillingPatcher {

    private val RETURN_ZERO_METHODS = listOf(
        "getResponseCode",
        "getBillingResponseCode"
    )

    private val RETURN_ONE_METHODS = listOf(
        "getPurchaseState"
    )

    private val RETURN_TRUE_METHODS = listOf(
        "verifyPurchase",
        "verifyValidSignature",
        "verifyPurchaseSignature",
        "validatePurchase",
        "isValidPurchase",
        "checkPurchaseSignature",
        "isAcknowledged",
        "isPurchased",
        "isOwned",
        "isPremium",
        "hasPurchased",
        "isAutoRenewing",
        "isSubscribed",
        "hasSubscription",
        "isProUser",
        "isFullVersion",
        "isUnlocked",
        "isPro",
        "isVip",
        "checkLicense",
        "hasLicense"
    )

    private val RETURN_FALSE_METHODS = listOf(
        "isTrialExpired",
        "isExpired",
        "needsPurchase",
        "shouldShowAds",
        "isFreeUser",
        "isFreeTier"
    )

    private val NOP_BRANCH_STRINGS = listOf(
        "BillingClient",
        "billingResult",
        "ITEM_ALREADY_OWNED",
        "SERVICE_DISCONNECTED",
        "BILLING_UNAVAILABLE",
        "USER_CANCELED",
        "DEVELOPER_ERROR",
        "onPurchasesUpdated",
        "onBillingSetupFinished",
        "queryPurchases",
        "launchBillingFlow",
        "acknowledgePurchase",
        "consumePurchase",
        "com.android.vending"
    )

    data class BillingPatchReport(
        val patchedMethods: List<String>,
        val bytesModified: Int
    )

    fun patch(dexBytes: ByteArray): Pair<ByteArray, BillingPatchReport> {
        val patched = dexBytes.copyOf()
        val patchedMethods = mutableListOf<String>()
        var bytesModified = 0

        for (method in RETURN_ZERO_METHODS) {
            val offsets = findStringOffsets(patched, method)
            for (offset in offsets) {
                val codeOffset = findMethodCodeOffset(patched, offset)
                if (codeOffset > 0) {
                    val n = injectReturnZero(patched, codeOffset)
                    if (n > 0) {
                        patchedMethods += "billing:$method@$offset"
                        bytesModified += n
                    }
                }
            }
        }

        for (method in RETURN_ONE_METHODS) {
            val offsets = findStringOffsets(patched, method)
            for (offset in offsets) {
                val codeOffset = findMethodCodeOffset(patched, offset)
                if (codeOffset > 0) {
                    val n = injectReturnOne(patched, codeOffset)
                    if (n > 0) {
                        patchedMethods += "billing:$method@$offset"
                        bytesModified += n
                    }
                }
            }
        }

        for (method in RETURN_TRUE_METHODS) {
            val offsets = findStringOffsets(patched, method)
            for (offset in offsets) {
                val codeOffset = findMethodCodeOffset(patched, offset)
                if (codeOffset > 0) {
                    val n = injectReturnTrue(patched, codeOffset)
                    if (n > 0) {
                        patchedMethods += "billing:$method@$offset"
                        bytesModified += n
                    }
                }
            }
        }

        for (method in RETURN_FALSE_METHODS) {
            val offsets = findStringOffsets(patched, method)
            for (offset in offsets) {
                val codeOffset = findMethodCodeOffset(patched, offset)
                if (codeOffset > 0) {
                    val n = injectReturnFalse(patched, codeOffset)
                    if (n > 0) {
                        patchedMethods += "billing:$method@$offset"
                        bytesModified += n
                    }
                }
            }
        }

        for (str in NOP_BRANCH_STRINGS) {
            val offsets = findStringOffsets(patched, str)
            for (offset in offsets) {
                val n = neutralizeBillingBranches(patched, offset, 96)
                if (n > 0) {
                    patchedMethods += "billing:branch($str)@$offset"
                    bytesModified += n
                }
            }
        }

        if (bytesModified > 0) DexUtil.updateIntegrity(patched)

        return Pair(patched, BillingPatchReport(patchedMethods, bytesModified))
    }

    private fun injectReturnZero(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size || offset < 112) return 0
        data[offset]     = 0x12
        data[offset + 1] = 0x00
        data[offset + 2] = 0x0F
        data[offset + 3] = 0x00
        return 4
    }

    private fun injectReturnOne(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size || offset < 112) return 0
        data[offset]     = 0x12
        data[offset + 1] = 0x10
        data[offset + 2] = 0x0F
        data[offset + 3] = 0x00
        return 4
    }

    private fun injectReturnTrue(data: ByteArray, offset: Int): Int = injectReturnOne(data, offset)

    private fun injectReturnFalse(data: ByteArray, offset: Int): Int = injectReturnZero(data, offset)

    private fun neutralizeBillingBranches(data: ByteArray, offset: Int, window: Int): Int {
        val end = minOf(data.size, offset + window)
        var patched = 0
        for (i in offset until end - 3) {
            val op = data[i].toInt() and 0xFF
            if (op in 0x32..0x3D || op == 0x38 || op == 0x39) {
                data[i]     = 0x00
                data[i + 1] = 0x00
                data[i + 2] = 0x00
                data[i + 3] = 0x00
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
