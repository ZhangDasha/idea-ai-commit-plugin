package com.example.aicommit.service

import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.ScoredCommitIntent
import com.example.aicommit.model.StructuredDiff
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitMessageGenerationServiceTest {

    private val structuredDiff = StructuredDiff(emptyList(), emptyList(), false, 0, 0, 0)

    private fun request() = CommitGenerationRequest(
        structuredDiff = structuredDiff,
        language = "Chinese",
        baseUrl = "https://api.deepseek.com/chat/completions",
        apiKey = "token",
        analysisModel = "deepseek-chat",
        composerModel = "deepseek-chat",
        confidenceThreshold = 0.55
    )

    @Test
    fun `generate should analyze score compose and validate`() {
        val analysisService = mockk<CommitIntentAnalysisService>()
        val scorer = mockk<CommitRuleScorer>()
        val composer = mockk<CommitMessageComposerService>()
        val validator = mockk<CommitMessageValidator>()
        val analyzed = CommitIntent(listOf("fix"), listOf("service"), "stabilize commit flow", "", "", 0.9)
        val scored = ScoredCommitIntent("fix", "service", "stabilize commit flow", "", "", 0.9)

        every { analysisService.analyze(any(), any(), any(), any(), any()) } returns analyzed
        every { scorer.score(analyzed, structuredDiff, 0.55) } returns scored
        every { composer.compose(any(), any(), any(), any(), scored, any()) } returns "fix(service): stabilize commit flow"
        every { validator.validateOrFallback("fix(service): stabilize commit flow", scored) } returns "fix(service): stabilize commit flow"

        val service = CommitMessageGenerationService(analysisService, scorer, composer, validator)
        val result = service.generate(request()) { }

        assertEquals("fix(service): stabilize commit flow", result)
        // 验证编排顺序：第二阶段只调用一次
        verify(exactly = 1) { composer.compose(any(), any(), any(), any(), scored, any()) }
    }

    @Test
    fun `generate should retry once then fallback when validation keeps failing`() {
        val analysisService = mockk<CommitIntentAnalysisService>()
        val scorer = mockk<CommitRuleScorer>()
        val composer = mockk<CommitMessageComposerService>()
        val validator = mockk<CommitMessageValidator>()
        val analyzed = CommitIntent(listOf("fix"), emptyList(), "x", "", "", 0.9)
        val scored = ScoredCommitIntent("fix", null, "x", "", "", 0.9)

        every { analysisService.analyze(any(), any(), any(), any(), any()) } returns analyzed
        every { scorer.score(analyzed, structuredDiff, 0.55) } returns scored
        every { composer.compose(any(), any(), any(), any(), scored, any()) } returns "bad output"
        every { validator.validateOrFallback("bad output", scored) } throws IllegalArgumentException("模型返回格式非法")
        every { validator.buildFallback(scored) } returns "fix: x"

        val service = CommitMessageGenerationService(analysisService, scorer, composer, validator)
        val result = service.generate(request()) { }

        assertEquals("fix: x", result)
        // 重试一次后仍失败 → 共调用 composer 两次
        verify(exactly = 2) { composer.compose(any(), any(), any(), any(), scored, any()) }
    }
}
