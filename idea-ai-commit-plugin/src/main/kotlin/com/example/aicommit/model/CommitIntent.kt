package com.example.aicommit.model

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
    val confidence: Double = 0.0
)

/**
 * 经本地规则层修正后的意图。
 * @param finalScope 模块分散时为 null（强制省略 scope）
 * @param fallbackReason 非空表示进入保守策略（如低置信度）
 */
data class ScoredCommitIntent(
    val finalType: String,
    val finalScope: String?,
    val normalizedIntent: String,
    val why: String,
    val impact: String,
    val confidence: Double,
    val fallbackReason: String? = null
)
