package com.example.aicommit.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitPromptBuilderTest {

    @Test
    fun `default template should contain key guidance phrases`() {
        val tpl = CommitPromptBuilder.DEFAULT_TEMPLATE
        assertTrue(tpl.contains("阅读以下代码变更"))
        assertTrue(tpl.contains("使用 {language}"))
        assertTrue(tpl.contains("subject 不超过 50 个字符"))
        assertTrue(tpl.contains("代码变更：\n{diff}"))
        assertTrue(tpl.contains("严禁输出代码块"))
    }

    @Test
    fun `build should replace placeholders with default template`() {
        val builder = CommitPromptBuilder()
        val prompt = builder.build(
            CommitPromptBuilder.DEFAULT_TEMPLATE,
            "中文",
            "diff --git a.txt b.txt"
        )
        assertTrue(prompt.contains("使用 中文"))
        assertTrue(prompt.contains("代码变更：\ndiff --git a.txt b.txt"))
        assertEquals(false, prompt.contains("{language}"))
        assertEquals(false, prompt.contains("{diff}"))
    }
}
