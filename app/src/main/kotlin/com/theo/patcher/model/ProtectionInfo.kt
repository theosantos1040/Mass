package com.theo.patcher.model

data class ProtectionInfo(
    val type: ProtectionType,
    val className: String,
    val detail: String,
    val offset: Int = -1
) {
    enum class ProtectionType {
        SIGNATURE_CHECK,
        INSTALLER_CHECK,
        INTEGRITY_CHECK,
        DEBUG_CHECK,
        ROOT_CHECK,
        EMULATOR_CHECK,
        TAMPER_CHECK,
        UNKNOWN
    }
}
