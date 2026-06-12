package com.example.aicommit.prompt

import kotlin.test.Test
import kotlin.test.assertEquals

class CommitPromptBuilderTest {

    @Test
    fun `build should replace language and diff placeholders`() {
        val builder = CommitPromptBuilder()
        val prompt = builder.build(
            template = "用{language}生成提交说明：{diff}",
            language = "中文",
            diff = "diff --git a.txt b.txt"
        )
        assertEquals("用中文生成提交说明：diff --git a.txt b.txt", prompt)
    }

    @Test
    fun `build should throw when template missing diff placeholder`() {
        val builder = CommitPromptBuilder()
        try {
            builder.build("no placeholder", "中文", "diff")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Prompt 必须包含 {diff}", e.message)
        }
    }

    @Test
    fun `build should trim result`() {
        val builder = CommitPromptBuilder()
        val prompt = builder.build("  {diff}  ", "中文", "content")
        assertEquals("content", prompt)
    }
}
