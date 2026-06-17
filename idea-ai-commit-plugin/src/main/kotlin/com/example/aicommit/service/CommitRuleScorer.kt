package com.example.aicommit.service

import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.ScoredCommitIntent
import com.example.aicommit.model.StructuredDiff

/**
 * 本地规则打分层：基于文件分布、路径特征、候选类型与置信度做有限兜底与排序。
 * 不做复杂业务推断，避免覆盖模型的正确判断。
 */
class CommitRuleScorer {

    fun score(
        intent: CommitIntent,
        structuredDiff: StructuredDiff,
        confidenceThreshold: Double
    ): ScoredCommitIntent {
        val files = structuredDiff.files

        // 文件维度规则：docs/test 优先强制；空文件集合不触发强制
        val docsOnly = files.isNotEmpty() && files.all { it.extension == "md" }
        val testsOnly = files.isNotEmpty() && files.all {
            it.relativePath.contains("/test/") || it.relativePath.contains("Test.kt")
        }

        // scope 收敛：模块分散时强制省略
        val modules = files.mapNotNull { it.module }
        val dispersed = modules.distinct().size > 1
        val dominantModule = files.groupingBy { it.module }.eachCount().maxByOrNull { it.value }?.key

        val forcedType = when {
            docsOnly -> "docs"
            testsOnly -> "test"
            intent.typeCandidates.firstOrNull() in ALLOWED_TYPES -> intent.typeCandidates.first()
            else -> "refactor"
        }

        val normalizedIntent = when {
            docsOnly -> "refresh plugin documentation"
            testsOnly -> "refine commit generation tests"
            intent.intent in VAGUE_INTENTS -> "reorganize commit message pipeline"
            else -> intent.intent
        }

        return ScoredCommitIntent(
            finalType = forcedType,
            finalScope = if (!dispersed && dominantModule != null) dominantModule else null,
            normalizedIntent = normalizedIntent,
            why = intent.why,
            impact = intent.impact,
            confidence = intent.confidence,
            fallbackReason = if (intent.confidence < confidenceThreshold) "low confidence intent" else null
        )
    }

    companion object {
        /** Conventional Commits 合法 type 集合 */
        private val ALLOWED_TYPES =
            setOf("feat", "fix", "refactor", "perf", "style", "docs", "test", "chore", "ci", "build")

        /** 缺少业务语义的空洞表述，需降级为通用描述 */
        private val VAGUE_INTENTS = setOf("update code", "modify files", "optimize logic")
    }
}
