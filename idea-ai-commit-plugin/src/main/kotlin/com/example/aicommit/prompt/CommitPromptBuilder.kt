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
阅读以下代码变更，生成 Conventional Commits 规范的 git commit message。

规则：
1. 使用 {language} 输出
2. subject 不超过 50 个字符，仅概括"做了什么"，不罗列文件/实现细节
3. 从按需选择：feat, fix, refactor, perf, style, docs, test, chore, ci, build
4. scope 仅在文件路径或改动意图明确指向某个模块时才使用，否则省略
5. 严禁输出代码块、解释、分析、提示语或任何多余文本
6. 禁止捏造不存在的变更或背景信息

输出格式（二选一）：

简单改动：
type: subject

复杂改动（仅当 subject 不足以说明意图时才用 body）：
type: subject

body paragraph

body 格式要求：
- body 与 subject 之间必须有且只有一行空行
- body 用中文自然段落描述，不用列表或 Markdown 格式
- body 仅写"为什么"和"影响范围"，不重复 subject 内容
- 如 subject 已足够表达改动意图，不输出 body

代码变更：
{diff}
"""
    }
}
