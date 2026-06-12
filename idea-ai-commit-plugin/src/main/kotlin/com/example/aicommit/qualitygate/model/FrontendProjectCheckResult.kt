package com.example.aicommit.qualitygate.model

/** 单个前端项目的检查结果汇总 */
data class FrontendProjectCheckResult(
    val projectRoot: String,
    val projectName: String,
    val checks: List<FrontendCheckResult>
)
