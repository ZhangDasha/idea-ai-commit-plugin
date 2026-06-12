package com.example.aicommit.prompt

/**
 * Prompt 模板组装：替换 {language} 和 {diff} 占位符
 */
class CommitPromptBuilder {

    fun build(template: String, language: String, diff: String, warningSummary: String? = null): String {
        require(template.contains("{diff}")) { "Prompt 必须包含 {diff}" }
        val base = template
            .replace("{language}", language)
            .replace("{diff}", diff)
        if (warningSummary.isNullOrBlank()) return base.trim()
        return buildString {
            append(base.trim())
            append("\n\n附加上下文（仅供参考，不必在提交说明中直接体现）：\n")
            append(warningSummary)
        }
    }

    companion object {
        const val DEFAULT_TEMPLATE = """
请基于以下代码变更生成一条 {language} commit message。
要求使用 Conventional Commits 风格，只输出提交说明正文，不输出代码块。

{diff}
"""
    }
}
