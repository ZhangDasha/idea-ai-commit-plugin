package com.example.aicommit.prompt

/**
 * Prompt 模板组装。
 * 旧单阶段：build(template, language, diff)
 * 两阶段：buildAnalysisPrompt（第一阶段意图归纳）/ buildComposerPrompt（第二阶段文案）
 */
class CommitPromptBuilder {

    /** 旧单阶段组装：替换 {language} 和 {diff} 占位符 */
    fun build(template: String, language: String, diff: String): String {
        require(template.contains("{diff}")) { "Prompt 必须包含 {diff}" }
        return template
            .replace("{language}", language)
            .replace("{diff}", diff)
            .trim()
    }

    /** 第一阶段：组装意图归纳 prompt（输入为结构化 diff 的 JSON） */
    fun buildAnalysisPrompt(language: String, structuredDiffJson: String): String =
        ANALYSIS_TEMPLATE
            .replace("{language}", language)
            .replace("{structured_diff}", structuredDiffJson)
            .trim()

    /** 第二阶段：组装文案生成 prompt（输入为规则层修正后的意图 JSON） */
    fun buildComposerPrompt(language: String, scoredIntentJson: String): String =
        COMPOSER_TEMPLATE
            .replace("{language}", language)
            .replace("{intent}", scoredIntentJson)
            .trim()

    companion object {
        /** 第一阶段 system 角色：仅约束输出 JSON */
        const val ANALYSIS_SYSTEM_PROMPT = "你是提交说明分析器。只能返回 JSON，不得输出解释、代码块或多余文本。"

        /** 第二阶段 system 角色：仅约束输出 Conventional Commits 文本 */
        const val COMPOSER_SYSTEM_PROMPT = "你是提交说明生成器。只返回 Conventional Commits 文本，不得输出解释、代码块或 Markdown。"

        const val ANALYSIS_TEMPLATE = """
阅读以下结构化代码变更，用 {language} 理解改动意图，仅返回 JSON。

要求：
1. typeCandidates：候选 type，按可能性排序，取值 feat/fix/refactor/perf/style/docs/test/chore/ci/build
2. scopeCandidates：候选 scope，仅在改动明确聚集某模块时给出，否则为空数组
3. intent：一句话概括本次改动目的（须含业务语义，禁止 update code 之类空洞表述）
4. why：改动原因（可为空）
5. impact：影响范围（可为空）
6. confidence：0 到 1 之间你对本次判断的置信度

结构化变更：
{structured_diff}
"""

        const val COMPOSER_TEMPLATE = """
请根据以下意图对象生成一条 {language} Conventional Commits 提交说明，只输出正文。

规则：
1. 格式 type: subject 或 type(scope): subject
2. subject 不超过 50 字符，仅概括"做了什么"
3. 仅当 why 或 impact 明显不足以被 subject 表达时才追加 body
4. 严禁输出代码块、解释、Markdown 标记或任何多余文本

意图对象：
{intent}
"""

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
