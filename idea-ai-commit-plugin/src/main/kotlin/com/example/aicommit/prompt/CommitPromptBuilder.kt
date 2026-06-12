package com.example.aicommit.prompt

/**
 * Prompt 模板组装：替换 {language} 和 {diff} 占位符
 */
class CommitPromptBuilder {

    fun build(template: String, language: String, diff: String): String {
        require(template.contains("{diff}")) { "Prompt 必须包含 {diff}" }
        return template
            .replace("{language}", language)
            .replace("{diff}", diff)
            .trim()
    }

    companion object {
        const val DEFAULT_TEMPLATE = """
请基于以下代码变更生成一条 {language} commit message。
要求使用 Conventional Commits 风格，只输出提交说明正文，不输出代码块。

{diff}
"""
    }
}
