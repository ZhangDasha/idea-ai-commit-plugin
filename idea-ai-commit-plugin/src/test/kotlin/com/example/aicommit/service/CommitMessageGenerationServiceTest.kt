package com.example.aicommit.service

import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.StructuredDiff
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.IOException

class CommitMessageGenerationServiceTest {
    private val diff = StructuredDiff(
        listOf(com.example.aicommit.model.ChangedFileSummary("src/Flow.kt", com.example.aicommit.model.ChangeStatus.MODIFIED,
            1, 0, "kt", "Kotlin", null)), emptyList(), false, 1, 1, 0
    )
    private val analyzed = CommitIntent(listOf("fix"), emptyList(), "stabilize commit flow", "", "", 0.9)
    private fun request() = CommitGenerationRequest(
        diff, "English", "u", "k", "analysis", "composer", 0.55,
        123, 456, "custom {structured_diff}", "custom {intent}"
    )

    @Test
    fun `generate forwards custom prompts and timeouts and returns valid message`() {
        val analysis = mockk<CommitIntentAnalysisService>()
        val composer = mockk<CommitMessageComposerService>()
        every { analysis.analyze(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns analyzed
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns "fix: stabilize commit flow"
        val service = CommitMessageGenerationService(analysisService = analysis, composer = composer, checkCanceled = {})
        assertEquals("fix: stabilize commit flow", service.generate(request()) {})
        verify { analysis.analyze(diff, "u", "k", "analysis", "English", "custom {structured_diff}", 123, 456, any()) }
        verify(exactly = 1) {
            composer.compose("u", "k", "composer", "English", any(), "custom {intent}", 123, 456, null, any())
        }
    }

    @Test
    fun `invalid first output retries with bounded feedback and falls back once`() {
        val analysis = mockk<CommitIntentAnalysisService>()
        val composer = mockk<CommitMessageComposerService>()
        every { analysis.analyze(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns analyzed
        val invalid = "bad output " + "x".repeat(10_000)
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), null, any()) } returns invalid
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), match {
            it.contains("Validation error:") && it.contains("bad output") && it.contains("untrusted") && it.length < 4_000
        }, any()) } returns "still invalid"
        val service = CommitMessageGenerationService(analysisService = analysis, composer = composer, checkCanceled = {})
        assertEquals("fix: stabilize commit flow", service.generate(request()) {})
        verify(exactly = 2) { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `retry network failure propagates to restore original message`() {
        val analysis = mockk<CommitIntentAnalysisService>()
        val composer = mockk<CommitMessageComposerService>()
        every { analysis.analyze(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns analyzed
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), null, any()) } returns "bad output"
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), ofType<String>(), any()) } throws IOException("network failed")
        val service = CommitMessageGenerationService(analysisService = analysis, composer = composer, checkCanceled = {})
        assertFailsWith<IOException> { service.generate(request()) {} }
    }

    @Test
    fun `low confidence body is rejected and final fallback has no body`() {
        val analysis = mockk<CommitIntentAnalysisService>()
        val composer = mockk<CommitMessageComposerService>()
        every { analysis.analyze(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns analyzed.copy(confidence = 0.2)
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            "fix: stabilize commit flow\n\nUnsupported impact"
        val service = CommitMessageGenerationService(analysisService = analysis, composer = composer, checkCanceled = {})
        assertEquals("fix: stabilize commit flow", service.generate(request()) {})
        verify(exactly = 2) { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `empty diff fails before analysis`() {
        val analysis = mockk<CommitIntentAnalysisService>()
        val service = CommitMessageGenerationService(analysisService = analysis, checkCanceled = {})
        assertFailsWith<IllegalArgumentException> {
            service.generate(request().copy(structuredDiff = StructuredDiff(emptyList(), emptyList(), true, 3, 0, 0))) {}
        }
        verify(exactly = 0) { analysis.analyze(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Chinese output retries English prose even if intermediate intent is English`() {
        val analysis = mockk<CommitIntentAnalysisService>()
        val composer = mockk<CommitMessageComposerService>()
        val sitemapIntent = analyzed.copy(typeCandidates = listOf("feat"), scopeCandidates = listOf("ReadSitemapService"),
            intent = "remove markdown parameter and reject markdown-only responses")
        every { analysis.analyze(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns sitemapIntent
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), null, any()) } returns
            "feat(ReadSitemapService): reject markdown-only responses"
        val chinese = "feat(ReadSitemapService): 移除 markdown 参数并拒绝仅含 Markdown 的响应"
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), match { it.contains("中文") }, any()) } returns chinese
        val service = CommitMessageGenerationService(analysisService = analysis, composer = composer, checkCanceled = {})
        assertEquals(chinese, service.generate(request().copy(language = "Chinese")) {})
        verify(exactly = 2) { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `overlong reported sitemap output is replaced by a validated Chinese final result`() {
        val analysis = mockk<CommitIntentAnalysisService>()
        val composer = mockk<CommitMessageComposerService>()
        every { analysis.analyze(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            analyzed.copy(typeCandidates = listOf("feat"), scopeCandidates = listOf("ReadSitemapService"))
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), null, any()) } returns
            "feat(ReadSitemapService): remove markdown parameter and reject markdown-only responses"
        val chinese = "feat(ReadSitemapService): 移除 markdown 参数并拒绝仅含 Markdown 的响应"
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), match { it.contains("86") && it.contains("72") }, any()) } returns chinese
        val service = CommitMessageGenerationService(analysisService = analysis, composer = composer, checkCanceled = {})
        assertEquals(chinese, service.generate(request().copy(language = "Chinese")) {})
    }

    @Test
    fun `Chinese fallback never copies untranslated English intent`() {
        val analysis = mockk<CommitIntentAnalysisService>()
        val composer = mockk<CommitMessageComposerService>()
        every { analysis.analyze(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns analyzed
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns "fix: stabilize commit flow"
        val service = CommitMessageGenerationService(analysisService = analysis, composer = composer, checkCanceled = {})
        assertFailsWith<IllegalArgumentException> { service.generate(request().copy(language = "Chinese")) {} }
    }

    @Test
    fun `repeat generation makes fresh requests rather than reusing prior text`() {
        val analysis = mockk<CommitIntentAnalysisService>()
        val composer = mockk<CommitMessageComposerService>()
        every { analysis.analyze(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns analyzed
        every { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returnsMany
            listOf("fix: stabilize commit flow", "fix: handle generation failures")
        val service = CommitMessageGenerationService(analysisService = analysis, composer = composer, checkCanceled = {})
        assertEquals("fix: stabilize commit flow", service.generate(request()) {})
        assertEquals("fix: handle generation failures", service.generate(request()) {})
        verify(exactly = 2) { analysis.analyze(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 2) { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancellation is checked between stages`() {
        val analysis = mockk<CommitIntentAnalysisService>()
        val composer = mockk<CommitMessageComposerService>()
        every { analysis.analyze(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns analyzed
        var checks = 0
        val service = CommitMessageGenerationService(analysisService = analysis, composer = composer, checkCanceled = {
            checks++
            if (checks == 2) throw java.util.concurrent.CancellationException()
        })
        assertFailsWith<java.util.concurrent.CancellationException> { service.generate(request()) {} }
        verify(exactly = 0) { composer.compose(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
