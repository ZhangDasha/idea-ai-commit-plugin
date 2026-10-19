package com.example.aicommit.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommitPromptBuilderTest {
    @Test
    fun `two stage templates describe evidence and conservative composition`() {
        assertTrue(CommitPromptBuilder.ANALYSIS_TEMPLATE.contains("## JSON output shape"))
        assertTrue(CommitPromptBuilder.ANALYSIS_TEMPLATE.contains("isBreaking"))
        assertTrue(CommitPromptBuilder.ANALYSIS_TEMPLATE.contains("untrusted"))
        assertTrue(CommitPromptBuilder.COMPOSER_TEMPLATE.contains("within 50 characters"))
        assertTrue(CommitPromptBuilder.COMPOSER_TEMPLATE.contains("terminal punctuation"))
    }

    @Test
    fun `build preserves legacy template replacement`() {
        val prompt = CommitPromptBuilder().build(
            CommitPromptBuilder.DEFAULT_TEMPLATE, "Chinese", "diff --git a.txt b.txt"
        )
        assertTrue(prompt.contains("Chinese"))
        assertTrue(prompt.contains("diff --git a.txt b.txt"))
        assertEquals(false, prompt.contains("{language}"))
        assertEquals(false, prompt.contains("{diff}"))
    }

    @Test
    fun `custom staged templates and validation feedback are forwarded`() {
        val builder = CommitPromptBuilder()
        assertEquals(
            "Chinese|json",
            builder.buildAnalysisPrompt("Chinese", "json", "{language}|{structured_diff}")
        )
        val prompt = builder.buildComposerPrompt(
            "Chinese", "intent", "{language}|{intent}", "bad header"
        )
        assertTrue(prompt.endsWith("## Validation feedback\nbad header"))
    }

    @Test
    fun `selected language is mandatory even without a template language placeholder`() {
        val builder = CommitPromptBuilder()
        // A persisted old/custom template may contain only the input placeholder.
        assertEquals("{}", builder.buildComposerPrompt("Chinese", "{}", "{intent}"))
        assertTrue(builder.buildAnalysisSystemPrompt("Chinese").contains("输出语言必须为简体中文"))
        assertTrue(builder.buildComposerSystemPrompt("Chinese").contains("输出语言必须为简体中文"))
        assertTrue(builder.buildComposerSystemPrompt("English").contains("in English"))
    }

    @Test
    fun `staged builders reject empty input`() {
        assertFailsWith<IllegalArgumentException> {
            CommitPromptBuilder().buildAnalysisPrompt("Chinese", "")
        }
    }
}
