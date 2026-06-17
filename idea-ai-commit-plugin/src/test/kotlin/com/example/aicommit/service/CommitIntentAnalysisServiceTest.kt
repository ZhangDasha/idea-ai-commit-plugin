package com.example.aicommit.service

import com.example.aicommit.llm.IntentAnalyzerClient
import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.StructuredDiff
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommitIntentAnalysisServiceTest {

    @Test
    fun `analyze should parse json intent payload`() {
        val client = mockk<IntentAnalyzerClient>()
        every {
            client.analyze(
                any(), any(), any(), any(), any(), any()
            )
        } returns """
            {"typeCandidates":["fix"],"scopeCandidates":["service"],"intent":"stabilize generation flow","why":"avoid malformed output","impact":"service layer","confidence":0.82}
        """.trimIndent()

        val service = CommitIntentAnalysisService(client)
        val result = service.analyze(
            structuredDiff = StructuredDiff(emptyList(), emptyList(), false, 0, 0, 0),
            baseUrl = "https://api.deepseek.com/chat/completions",
            apiKey = "token",
            model = "deepseek-chat",
            language = "Chinese"
        )

        assertEquals(
            CommitIntent(
                typeCandidates = listOf("fix"),
                scopeCandidates = listOf("service"),
                intent = "stabilize generation flow",
                why = "avoid malformed output",
                impact = "service layer",
                confidence = 0.82
            ),
            result
        )
    }

    @Test
    fun `analyze should fail when typeCandidates missing`() {
        val client = mockk<IntentAnalyzerClient>()
        every { client.analyze(any(), any(), any(), any(), any(), any()) } returns
            """{"typeCandidates":[],"scopeCandidates":[],"intent":"x","why":"","impact":"","confidence":0.1}"""

        val service = CommitIntentAnalysisService(client)
        assertFailsWith<IllegalArgumentException> {
            service.analyze(
                structuredDiff = StructuredDiff(emptyList(), emptyList(), false, 0, 0, 0),
                baseUrl = "u", apiKey = "k", model = "m", language = "Chinese"
            )
        }
    }

    @Test
    fun `analyze should fail when payload is not json`() {
        val client = mockk<IntentAnalyzerClient>()
        every { client.analyze(any(), any(), any(), any(), any(), any()) } returns "not a json"

        val service = CommitIntentAnalysisService(client)
        assertFailsWith<Exception> {
            service.analyze(
                structuredDiff = StructuredDiff(emptyList(), emptyList(), false, 0, 0, 0),
                baseUrl = "u", apiKey = "k", model = "m", language = "Chinese"
            )
        }
    }
}
