package com.theo.patcher.model

data class AdInfo(
    val sdkName: String,
    val sdkPackage: String,
    val components: List<String>,
    val offset: Int = -1
)
