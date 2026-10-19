package com.example.aicommit.git

/**
 * diff 收集限制参数
 * maxChars: 最大 diff 字符数（防止撑爆 LLM 上下文）
 * maxUnversionedBytes: 新增文件大小上限（字节）
 * contextLines: git diff 上下文行数
 */
data class DiffLimits(
    val maxChars: Int = 100_000,
    val maxUnversionedBytes: Int = 20_000,
    val contextLines: Int = 3,
    val processTimeoutMillis: Long = 30_000
) {
    init {
        require(maxChars > 0 && maxUnversionedBytes > 0 && contextLines >= 0 && processTimeoutMillis > 0)
    }
}
