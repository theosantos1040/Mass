package com.theo.patcher.model

data class LicenseInfo(
    val type: LicenseType,
    val className: String,
    val methodName: String,
    val offset: Int = -1
) {
    enum class LicenseType {
        GOOGLE_LVL,
        SERVER_MANAGED,
        STRICT_POLICY,
        CUSTOM,
        UNKNOWN
    }
}
