// model/PurchaseInfo.kt — IAP/purchase detection data model
package com.theo.patcher.model

data class PurchaseInfo(
    val productId: String,
    val type: PurchaseType,
    val validationMethod: ValidationMethod,
    val className: String,
    val methodName: String,
    val offset: Int = -1
) {
    enum class PurchaseType {
        ONE_TIME,
        SUBSCRIPTION,
        CONSUMABLE,
        UNKNOWN
    }

    enum class ValidationMethod {
        GOOGLE_PLAY_BILLING_V4,
        GOOGLE_PLAY_BILLING_V3,
        SERVER_SIDE,
        LOCAL_SIGNATURE,
        STATIC_RESPONSE,
        UNKNOWN
    }
}
