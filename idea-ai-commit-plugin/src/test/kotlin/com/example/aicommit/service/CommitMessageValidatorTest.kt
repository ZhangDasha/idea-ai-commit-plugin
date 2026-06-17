package com.example.aicommit.service

import com.example.aicommit.model.ScoredCommitIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommitMessageValidatorTest {

    private val validator = CommitMessageValidator()

    @Test
    fun `validate should fallback for blank output`() {
        val intent = ScoredCommitIntent(
            "refactor", null, "reorganize commit message pipeline", "", "", 0.20, "low confidence intent"
        )
        assertEquals("refactor: reorganize commit message pipeline", validator.validateOrFallback("   ", intent))
    }

    @Test
    fun `validate should accept well formed message`() {
        val intent = ScoredCommitIntent("fix", "service", "stabilize commit flow", "", "", 0.9)
        assertEquals("fix(service): stabilize commit flow", validator.validateOrFallback("fix(service): stabilize commit flow", intent))
    }

    @Test
    fun `validate should reject markdown formatted result`() {
        val intent = ScoredCommitIntent("fix", null, "stabilize commit flow", "", "", 0.8)
        val error = assertFailsWith<IllegalArgumentException> {
            validator.validateOrFallback("### fix: stabilize commit flow", intent)
        }
        assertEquals("模型返回了非提交说明文本", error.message)
    }

    @Test
    fun `validate should reject illegal format`() {
        val intent = ScoredCommitIntent("fix", null, "stabilize commit flow", "", "", 0.8)
        val error = assertFailsWith<IllegalArgumentException> {
            validator.validateOrFallback("updated some files", intent)
        }
        assertEquals("模型返回格式非法", error.message)
    }
}
