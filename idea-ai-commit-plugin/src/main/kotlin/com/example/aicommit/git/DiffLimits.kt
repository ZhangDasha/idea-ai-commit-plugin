package com.example.aicommit.git

/**
 * diff 收集限制参数
 */
data class DiffLimits(
    val maxChars: Int = 60_000,
    val maxUnversionedBytes: Int = 20_000
)
