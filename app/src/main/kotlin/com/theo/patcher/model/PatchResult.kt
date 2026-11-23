// model/PatchResult.kt — result returned after patching completes
package com.theo.patcher.model

import java.io.File

data class PatchResult(
    val success: Boolean,
    val outputApk: File?,
    val appliedStrategies: List<String>,
    val log: String,
    val error: String? = null,
    val splitApks: List<File> = emptyList()
)
