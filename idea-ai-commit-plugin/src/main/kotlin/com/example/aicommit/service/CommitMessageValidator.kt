package com.example.aicommit.service

import com.example.aicommit.model.ScoredCommitIntent

/**
 * 结果校验器：写回提交框前做格式校验，非法则交给上层重试或回退。
 * 回退优先保证"正确、保守、可提交"，而非表达丰富。
 */
class CommitMessageValidator {

    /** Conventional Commits 基本格式：type 或 type(scope) 后接 subject */
    private val formatRegex =
        Regex("^(feat|fix|refactor|perf|style|docs|test|chore|ci|build)(\\([^)]+\\))?: .+")

    /**
     * 校验输出，合法则原样返回，空则回退，非法格式/非提交文本则抛异常（交上层重试）。
     */
    fun validateOrFallback(message: String, intent: ScoredCommitIntent): String {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return buildFallback(intent)
        // 拦截代码块与 Markdown 标记
        require(!trimmed.contains("```") && !trimmed.startsWith("#")) { "模型返回了非提交说明文本" }
        require(trimmed.matches(formatRegex)) { "模型返回格式非法" }
        return trimmed
    }

    /** 基于修正后的意图构建保守回退文案 */
    fun buildFallback(intent: ScoredCommitIntent): String {
        val scope = intent.finalScope?.let { "($it)" }.orEmpty()
        return "${intent.finalType}$scope: ${intent.normalizedIntent}".trim()
    }
}
