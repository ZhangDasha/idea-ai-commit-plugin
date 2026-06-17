package com.example.aicommit.service

import com.example.aicommit.model.ChangeStatus
import com.example.aicommit.model.ChangedFileSummary
import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.StructuredDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommitRuleScorerTest {

    private val scorer = CommitRuleScorer()

    @Test
    fun `score should force docs type for docs only changes`() {
        val diff = StructuredDiff(
            files = listOf(
                ChangedFileSummary("docs/README.md", ChangeStatus.MODIFIED, 10, 2, "md", "Markdown", "docs")
            ),
            keyHunks = emptyList(),
            truncated = false,
            totalFiles = 1,
            totalAdditions = 10,
            totalDeletions = 2
        )
        // intent 为空洞表述且候选为 fix，仍应被 docs 规则覆盖
        val intent = CommitIntent(listOf("fix"), listOf("docs"), "update code", "", "", 0.90)

        val result = scorer.score(intent, diff, 0.55)

        assertEquals("docs", result.finalType)
        assertEquals("refresh plugin documentation", result.normalizedIntent)
        assertEquals("docs", result.finalScope)
    }

    @Test
    fun `score should force test type for test only changes`() {
        val diff = StructuredDiff(
            files = listOf(
                ChangedFileSummary(
                    "src/test/kotlin/com/example/aicommit/service/FooTest.kt",
                    ChangeStatus.MODIFIED, 3, 1, "kt", "Kotlin", "service"
                )
            ),
            keyHunks = emptyList(), truncated = false, totalFiles = 1, totalAdditions = 3, totalDeletions = 1
        )
        val intent = CommitIntent(listOf("refactor"), emptyList(), "adjust tests", "", "", 0.70)

        val result = scorer.score(intent, diff, 0.55)

        assertEquals("test", result.finalType)
        assertEquals("refine commit generation tests", result.normalizedIntent)
    }

    @Test
    fun `score should drop scope when modules are dispersed`() {
        val diff = StructuredDiff(
            files = listOf(
                ChangedFileSummary("src/main/kotlin/com/example/aicommit/service/A.kt", ChangeStatus.MODIFIED, 2, 1, "kt", "Kotlin", "service"),
                ChangedFileSummary("src/main/kotlin/com/example/aicommit/settings/B.kt", ChangeStatus.MODIFIED, 2, 1, "kt", "Kotlin", "settings")
            ),
            keyHunks = emptyList(), truncated = false, totalFiles = 2, totalAdditions = 4, totalDeletions = 2
        )
        val intent = CommitIntent(listOf("refactor"), listOf("service"), "stabilize commit pipeline", "", "", 0.40)

        val result = scorer.score(intent, diff, 0.55)

        assertEquals("refactor", result.finalType)
        assertNull(result.finalScope)
        assertEquals("low confidence intent", result.fallbackReason)
    }
}
