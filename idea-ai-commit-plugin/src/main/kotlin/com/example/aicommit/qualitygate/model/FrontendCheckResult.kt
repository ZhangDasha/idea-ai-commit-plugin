package com.example.aicommit.qualitygate.model

/**
 * 单项前端检查结果
 * @param type 检查类型
 * @param command 执行的命令（未识别时为 null）
 * @param status 严重级别
 * @param exitCode 进程退出码（超时/未执行时为 null）
 * @param durationMillis 执行耗时
 * @param summary 关键输出摘要
 * @param details 完整截断输出
 */
data class FrontendCheckResult(
    val type: FrontendCheckType,
    val command: String?,
    val status: FrontendCheckStatus,
    val exitCode: Int?,
    val durationMillis: Long,
    val summary: String,
    val details: String
)
