package com.example.aicommit.qualitygate.model

/**
 * 一次质量闸口检查的完整会话结果
 * @param projects 各项目检查结果
 * @param hasCritical 是否存在严重问题
 * @param hasWarnings 是否存在警告
 * @param warningSummaryForPrompt 用于附加到 AI prompt 的警告摘要（无警告时为 null）
 * @param requiresConfirmation 是否需要用户确认（存在严重问题时为 true）
 */
data class FrontendCheckSessionResult(
    val projects: List<FrontendProjectCheckResult>,
    val hasCritical: Boolean,
    val hasWarnings: Boolean,
    val warningSummaryForPrompt: String?,
    val requiresConfirmation: Boolean
)
