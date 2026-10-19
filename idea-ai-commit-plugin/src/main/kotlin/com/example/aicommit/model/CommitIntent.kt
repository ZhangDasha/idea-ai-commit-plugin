package com.example.aicommit.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 提交意图数据模型，贯穿两阶段生成链路。
 */

/**
 * 第一阶段模型输出的结构化意图。
 * 全字段默认值：兼容 Jackson 反序列化（data class 无无参构造时会抛 InvalidDefinitionException）。
 */
data class CommitIntent(
    val typeCandidates: List<String> = emptyList(),
    val scopeCandidates: List<String> = emptyList(),
    val intent: String = "",
    val why: String = "",
    val impact: String = "",
    val confidence: Double = 0.0,
    @get:JsonProperty("isBreaking")
    val isBreaking: Boolean = false,
    val breakingChange: String = ""
)

/**
 * 经本地规则层修正后的意图。
 * @param finalScope Valid explicit candidate, omitted for dispersed modules or low confidence.
 * @param fallbackReason 非空表示进入保守策略（如低置信度）
 * @param isBreaking Explicit breaking status, consistent with the supplied description.
 * @param breakingChange Supplied migration/compatibility description, never inferred from paths.
 */
data class ScoredCommitIntent(
    val finalType: String,
    val finalScope: String?,
    val normalizedIntent: String,
    val why: String,
    val impact: String,
    val confidence: Double,
    val fallbackReason: String? = null,
    @get:JsonProperty("isBreaking")
    val isBreaking: Boolean = false,
    val breakingChange: String = ""
)

/** Shared format and semantic guards for scoring and final validation. */
internal object CommitIntentRules {
    val allowedTypes = setOf("feat", "fix", "refactor", "perf", "style", "docs", "test", "chore", "ci", "build", "revert")
    private val scopePattern = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}._/-]*$")
    private val unknownIntents = setOf("unknown", "unspecified", "n/a", "null", "未知", "无", "update code", "modify files", "optimize logic", "更新代码", "修改文件", "相关优化", "进行优化")

    fun validScope(scope: String): Boolean = scopePattern.matches(scope)

    fun requireSemanticIntent(text: String) {
        val semanticKey = text.trim().trimEnd { !it.isLetterOrDigit() }.lowercase()
        require(text.isNotBlank() && semanticKey !in unknownIntents &&
            text.any { it.isLetterOrDigit() }) { "缺少明确的提交意图" }
    }
}
