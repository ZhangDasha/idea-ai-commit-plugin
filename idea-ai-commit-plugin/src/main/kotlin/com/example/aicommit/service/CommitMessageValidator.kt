package com.example.aicommit.service

import com.example.aicommit.model.CommitIntentRules
import com.example.aicommit.model.ScoredCommitIntent

/** Validates Conventional Commits before writeback; fallback uses only supplied intent. */
class CommitMessageValidator {
    private val headerPattern = Regex("^([a-z]+)(?:\\(([^()]+)\\))?(!)?: (.+)$")
    private val breakingFooterPattern = Regex("^BREAKING(?: CHANGE|-CHANGE): (\\S.*)$")
    private val footerPattern = Regex("^[A-Za-z][A-Za-z0-9-]*(?:: | #)\\S.*$")
    private val markdownLinePattern = Regex("^\\s*(?:#{1,6}\\s|[-*+]\\s|\\d+[.)]\\s).*")
    private val trailingPunctuation = ".,;:!?。，；：！？、…"
    private val explanationPattern = Regex("(?i)^(?:explanation|here (?:is|are)|commit message|说明|解释|提交信息)[:： ].*")

    fun validateOrFallback(message: String, intent: ScoredCommitIntent, language: String? = null): String {
        validateIntent(intent)
        val trimmed = message.trim()
        if (trimmed.isBlank()) return buildFallback(intent, language)
        require(!trimmed.contains("```") && !trimmed.contains("~~~") && !trimmed.startsWith("#")) {
            "模型返回了非提交说明文本"
        }
        val lines = trimmed.replace("\r\n", "\n").split('\n')
        val header = lines.first()
        val parsed = headerPattern.matchEntire(header)
        require(parsed != null) { "模型返回格式非法" }
        val (type, scope, bang, subject) = parsed.destructured
        require(type in CommitIntentRules.allowedTypes && type == intent.finalType &&
            scope.ifEmpty { null } == intent.finalScope) { "提交类型或 scope 与意图不一致" }
        require(header.length <= 72) {
            "提交标题为 ${header.length} 字符，上限 72；当前 subject 最多 ${72 - (header.length - subject.length)} 字符，请重新概括核心改动"
        }
        require(header.none { it.isISOControl() } &&
            subject.isNotBlank() && subject == subject.trim() &&
            subject.last() !in trailingPunctuation && subject.any { it.isLetterOrDigit() }) { "提交标题非法" }
        require(!explanationPattern.matches(subject)) { "模型返回了非提交说明文本" }
        language?.let { CommitLanguage.validate(subject, it, "subject") }
        require(lines.size == 1 || lines[1].isBlank()) { "正文或 footer 前必须有空行" }

        var hasBreakingFooter = false
        var hasBody = false
        val bodyLines = mutableListOf<String>()
        val breakingLines = mutableListOf<String>()
        var inBreakingFooter = false
        var inFooter = false
        for ((index, line) in lines.withIndex().drop(2)) {
            require(!explanationPattern.matches(line) && !markdownLinePattern.matches(line) &&
                !headerPattern.matches(line)) { "模型返回了非提交说明文本" }
            if (line.isBlank()) {
                inFooter = false
                inBreakingFooter = false
                continue
            }
            val canStartFooter = inFooter || lines[index - 1].isBlank()
            if (line.startsWith("BREAKING CHANGE") || line.startsWith("BREAKING-CHANGE")) {
                require(breakingFooterPattern.matches(line) && canStartFooter) {
                    "BREAKING CHANGE footer 非法"
                }
                hasBreakingFooter = true
                inFooter = true
                inBreakingFooter = true
                breakingLines.add(breakingFooterPattern.matchEntire(line)!!.groupValues[1])
            } else if (footerPattern.matches(line) && canStartFooter) {
                inFooter = true
                inBreakingFooter = false
            } else if (!inFooter) {
                hasBody = true
                bodyLines.add(line)
            } else if (inBreakingFooter) {
                breakingLines.add(line)
            }
        }
        language?.let {
            if (bodyLines.isNotEmpty()) CommitLanguage.validate(bodyLines.joinToString("\n"), it, "body")
            if (breakingLines.isNotEmpty()) CommitLanguage.validate(breakingLines.joinToString("\n"), it, "BREAKING CHANGE 说明")
        }
        require(!hasBody || (intent.fallbackReason == null &&
            (intent.why.isNotBlank() || intent.impact.isNotBlank()))) { "正文缺少已确认的原因或影响" }
        val breaking = intent.isBreaking || intent.breakingChange.isNotBlank()
        require((bang.isNotEmpty() || hasBreakingFooter) == breaking) { "破坏性变更标记与意图不一致" }
        return trimmed
    }

    /** Fallback preserves complete supplied intent; an unsafe shortening restores the user's input. */
    fun buildFallback(intent: ScoredCommitIntent, language: String? = null): String {
        validateIntent(intent)
        val breaking = intent.isBreaking || intent.breakingChange.isNotBlank()
        val prefix = buildString {
            append(intent.finalType)
            intent.finalScope?.let { append("($it)") }
            if (breaking) append('!')
            append(": ")
        }
        val available = 72 - prefix.length
        require(available > 0) { "提交类型或 scope 过长" }
        val original = intent.normalizedIntent.trim().replace(Regex("\\s+"), " ")
        require(!original.contains("```") && !original.contains("~~~")) { "提交意图包含代码块" }
        val subject = original.trimEnd().trimEnd { it in trailingPunctuation }.trimEnd()
        require(subject.length <= available) { "无法在标题限制内保留完整意图，请精简生成提示词或 scope" }
        CommitIntentRules.requireSemanticIntent(subject)
        val footer = intent.breakingChange.trim().replace(Regex("\\s+"), " ")
        val fallback = prefix + subject + if (footer.isNotEmpty()) "\n\nBREAKING CHANGE: $footer" else ""
        // Ensure fallback obeys the very same contract as generated output.
        return validateOrFallback(fallback, intent, language)
    }

    private fun validateIntent(intent: ScoredCommitIntent) {
        CommitIntentRules.requireSemanticIntent(intent.normalizedIntent)
        require(intent.isBreaking || intent.breakingChange.isBlank()) { "破坏性变更标记与说明不一致" }
        require(intent.finalType in CommitIntentRules.allowedTypes) { "提交类型非法" }
        require(intent.finalScope == null || CommitIntentRules.validScope(intent.finalScope)) { "提交 scope 非法" }
    }
}
