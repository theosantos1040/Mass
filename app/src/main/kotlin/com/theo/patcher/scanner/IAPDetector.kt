// scanner/IAPDetector.kt — scans APK DEX for IAP/billing patterns
package com.theo.patcher.scanner

import com.theo.patcher.model.PurchaseInfo
import java.io.File
import java.util.zip.ZipFile

object IAPDetector {

    private val BILLING_PACKAGE_PATTERNS = listOf(
        "com/android/billingclient/api",
        "com/android/vending/billing",
        "com/google/android/gms/ads/purchase",
        "android/app/PendingIntent",
    )

    private val VALIDATION_METHOD_PATTERNS = mapOf(
        "verifyPurchase"          to PurchaseInfo.ValidationMethod.LOCAL_SIGNATURE,
        "verifyValidSignature"    to PurchaseInfo.ValidationMethod.LOCAL_SIGNATURE,
        "verifyPurchaseSignature" to PurchaseInfo.ValidationMethod.LOCAL_SIGNATURE,
        "validatePurchase"        to PurchaseInfo.ValidationMethod.SERVER_SIDE,
        "checkPurchase"           to PurchaseInfo.ValidationMethod.SERVER_SIDE,
        "onPurchasesUpdated"      to PurchaseInfo.ValidationMethod.GOOGLE_PLAY_BILLING_V4,
        "handlePurchase"          to PurchaseInfo.ValidationMethod.GOOGLE_PLAY_BILLING_V4,
        "consumePurchase"         to PurchaseInfo.ValidationMethod.GOOGLE_PLAY_BILLING_V4,
        "acknowledgePurchase"     to PurchaseInfo.ValidationMethod.GOOGLE_PLAY_BILLING_V4,
        "queryPurchasesAsync"     to PurchaseInfo.ValidationMethod.GOOGLE_PLAY_BILLING_V4,
        "buy"                     to PurchaseInfo.ValidationMethod.GOOGLE_PLAY_BILLING_V3,
        "getPurchases"            to PurchaseInfo.ValidationMethod.GOOGLE_PLAY_BILLING_V3,
    )

    private val PRODUCT_ID_PREFIXES = listOf("android.test.", "com.", "io.", "net.", "org.")

    fun detect(apkFile: File): List<PurchaseInfo> {
        val results = mutableListOf<PurchaseInfo>()
        try {
            ZipFile(apkFile).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .forEach { entry ->
                        val dexBytes = zip.getInputStream(entry).readBytes()
                        results += scanDex(dexBytes, entry.name)
                    }
            }
        } catch (e: Exception) {}
        return results.distinctBy { "${it.className}::${it.methodName}" }
    }

    private fun scanDex(dex: ByteArray, dexName: String): List<PurchaseInfo> {
        val found = mutableListOf<PurchaseInfo>()
        val text = buildString {
            var i = 0
            while (i < dex.size) {
                val b = dex[i].toInt() and 0xFF
                if (b in 32..126) {
                    val start = i
                    val sb = StringBuilder()
                    while (i < dex.size && (dex[i].toInt() and 0xFF) in 32..126) {
                        sb.append(dex[i].toInt().toChar())
                        i++
                    }
                    if (sb.length > 5) append(sb).append('\n')
                } else {
                    i++
                }
            }
        }

        val hasBillingLib = BILLING_PACKAGE_PATTERNS.any { text.contains(it) }
        if (!hasBillingLib) return found

        val billingVersion = when {
            text.contains("BillingClient") -> PurchaseInfo.ValidationMethod.GOOGLE_PLAY_BILLING_V4
            text.contains("com/android/vending/billing/IInAppBillingService") ->
                PurchaseInfo.ValidationMethod.GOOGLE_PLAY_BILLING_V3
            else -> PurchaseInfo.ValidationMethod.UNKNOWN
        }

        VALIDATION_METHOD_PATTERNS.forEach { (method, valMethod) ->
            if (text.contains(method)) {
                val offset = findStringOffset(dex, method)
                val className = extractClassName(text, method)
                found += PurchaseInfo(
                    productId = extractProductId(text),
                    type = detectPurchaseType(text),
                    validationMethod = if (valMethod == PurchaseInfo.ValidationMethod.GOOGLE_PLAY_BILLING_V4)
                        billingVersion else valMethod,
                    className = className,
                    methodName = method,
                    offset = offset
                )
            }
        }

        if (text.contains("https://") &&
            (text.contains("purchase") || text.contains("validate") || text.contains("verify"))) {
            found += PurchaseInfo(
                productId = extractProductId(text),
                type = PurchaseInfo.PurchaseType.UNKNOWN,
                validationMethod = PurchaseInfo.ValidationMethod.SERVER_SIDE,
                className = dexName,
                methodName = "serverValidation",
                offset = -1
            )
        }

        return found
    }

    private fun findStringOffset(dex: ByteArray, needle: String): Int {
        val needleBytes = needle.toByteArray()
        outer@ for (i in 0..dex.size - needleBytes.size) {
            for (j in needleBytes.indices) {
                if (dex[i + j] != needleBytes[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun extractClassName(text: String, method: String): String {
        val lines = text.lines()
        val methodLine = lines.indexOfFirst { it.contains(method) }
        if (methodLine > 0) {
            for (i in methodLine - 1 downTo maxOf(0, methodLine - 5)) {
                val line = lines[i].trim()
                if (line.contains("/") && line.contains(";")) return line
                if (line.contains("L") && line.endsWith(";")) return line
            }
        }
        return "unknown"
    }

    private fun extractProductId(text: String): String {
        val lines = text.lines()
        for (prefix in PRODUCT_ID_PREFIXES) {
            val line = lines.firstOrNull {
                it.trim().startsWith(prefix) &&
                (it.contains("premium") || it.contains("pro") || it.contains("unlock") ||
                 it.contains("subscription") || it.contains("purchase") || it.contains("buy") ||
                 it.contains("coin") || it.contains("gem") || it.contains("credit"))
            }
            if (line != null) return line.trim().take(64)
        }
        return "com.app.iap_product"
    }

    private fun detectPurchaseType(text: String): PurchaseInfo.PurchaseType {
        return when {
            text.contains("subs", ignoreCase = true) ||
            text.contains("subscription", ignoreCase = true) ||
            text.contains("monthly", ignoreCase = true) ||
            text.contains("annual", ignoreCase = true) -> PurchaseInfo.PurchaseType.SUBSCRIPTION

            text.contains("consumable", ignoreCase = true) ||
            text.contains("coin", ignoreCase = true) ||
            text.contains("gem", ignoreCase = true) ||
            text.contains("credit", ignoreCase = true) ||
            text.contains("token", ignoreCase = true) -> PurchaseInfo.PurchaseType.CONSUMABLE

            text.contains("premium", ignoreCase = true) ||
            text.contains("unlock", ignoreCase = true) ||
            text.contains("pro", ignoreCase = true) ||
            text.contains("full", ignoreCase = true) -> PurchaseInfo.PurchaseType.ONE_TIME

            else -> PurchaseInfo.PurchaseType.UNKNOWN
        }
    }
}
