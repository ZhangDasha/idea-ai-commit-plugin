package com.example.aicommit.service

import com.example.aicommit.llm.IntentAnalyzerClient
import com.example.aicommit.model.StructuredDiff
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommitIntentAnalysisServiceTest {
    private val diff = StructuredDiff(
        listOf(com.example.aicommit.model.ChangedFileSummary("src/Flow.kt", com.example.aicommit.model.ChangeStatus.MODIFIED,
            1, 0, "kt", "Kotlin", null)), emptyList(), false, 1, 1, 0
    )
    private val valid = """{"typeCandidates":["fix"],"scopeCandidates":[],"intent":"stabilize generation flow","why":"","impact":"","confidence":0.82,"isBreaking":false,"breakingChange":""}"""

    @Test
    fun `analyze parses all fields and forwards custom template and timeouts`() {
        val client = mockk<IntentAnalyzerClient>()
        every { client.analyze(any(), any(), any(), any(), any(), any(), any(), any()) } returns valid
        val result = CommitIntentAnalysisService(client).analyze(
            diff, "u", "k", "m", "Chinese", "{language}|{structured_diff}", 123, 456
        )
        assertEquals("stabilize generation flow", result.intent)
        assertEquals(0.82, result.confidence)
        verify {
            client.analyze("u", "k", "m", match { it.contains("输出语言必须为简体中文") }, match { it.startsWith("Chinese|") }, 123, 456, any())
        }
    }

    @Test
    fun `empty diff fails before network`() {
        val client = mockk<IntentAnalyzerClient>()
        assertFailsWith<IllegalArgumentException> {
            CommitIntentAnalysisService(client).analyze(
                StructuredDiff(emptyList(), emptyList(), false, 0, 0, 0), "u", "k", "m", "Chinese"
            )
        }
        verify(exactly = 0) { client.analyze(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `malformed missing or invalid fields fail`() {
        val client = mockk<IntentAnalyzerClient>()
        val service = CommitIntentAnalysisService(client)
        val invalid = listOf(
            valid + "\nadditional explanation",
            valid + "\n{}",
            "not json", "{}", valid.replace("[\"fix\"]", "[]"),
            valid.replace("\"isBreaking\":false", "\"isBreaking\":true"),
            valid.replace("\"breakingChange\":\"\"", "\"breakingChange\":\"Use v2\""),
            valid.replace("[\"fix\"]", "[\"unknown\"]"),
            valid.replace("0.82", "1.2"), valid.replace("stabilize generation flow", " "),
            valid.replace(",\"isBreaking\":false", ""), valid.replace("0.82", "null")
        )
        for (raw in invalid) {
            every { client.analyze(any(), any(), any(), any(), any(), any(), any(), any()) } returns raw
            assertFailsWith<Exception>(raw) { service.analyze(diff, "u", "k", "m", "Chinese") }
        }
    }
}
