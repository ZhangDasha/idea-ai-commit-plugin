package com.example.aicommit.service

import com.example.aicommit.model.ChangeStatus
import com.example.aicommit.model.ChangedFileSummary
import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.StructuredDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CommitRuleScorerTest {
    private val scorer = CommitRuleScorer()

    private fun diff(vararg modules: String, path: String = "src/main/A.kt", extension: String = "kt") = StructuredDiff(
        files = modules.map { ChangedFileSummary(path, ChangeStatus.MODIFIED, 2, 1, extension, "", it) },
        keyHunks = emptyList(), truncated = false, totalFiles = modules.size,
        totalAdditions = modules.size * 2, totalDeletions = modules.size
    )

    @Test
    fun `documentation and test rules preserve original intent and language`() {
        val docs = scorer.score(CommitIntent(listOf("fix"), listOf("docs"), "补充安装说明", confidence = .9),
            diff("docs", path = "docs/README.md", extension = "md"), .55)
        assertEquals("docs", docs.finalType)
        assertEquals("补充安装说明", docs.normalizedIntent)
        assertEquals("docs", docs.finalScope)
        val tests = scorer.score(CommitIntent(listOf("refactor"), intent = "cover expired sessions", confidence = .9),
            diff("auth", path = "src/test/SessionTest.kt"), .55)
        assertEquals("test", tests.finalType)
        assertEquals("cover expired sessions", tests.normalizedIntent)
        assertNull(tests.finalScope)
    }

    @Test
    fun `scope must be explicitly valid and modules must not be dispersed`() {
        val base = CommitIntent(listOf("fix"), intent = "repair session expiry", confidence = .9)
        assertNull(scorer.score(base, diff("auth"), .55).finalScope)
        assertEquals("session", scorer.score(base.copy(scopeCandidates = listOf("session")), diff("auth"), .55).finalScope)
        assertNull(scorer.score(base.copy(scopeCandidates = listOf("bad scope", "x)")), diff("auth"), .55).finalScope)
        assertNull(scorer.score(base.copy(scopeCandidates = listOf("session")), diff("auth", "ui"), .55).finalScope)
    }

    @Test
    fun `low confidence drops optional claims but keeps intent`() {
        val result = scorer.score(CommitIntent(listOf("fix"), listOf("auth"), "修复会话过期", "避免失败", "所有用户", .4), diff("auth"), .55)
        assertEquals("修复会话过期", result.normalizedIntent)
        assertNull(result.finalScope)
        assertEquals("", result.why)
        assertEquals("", result.impact)
        assertEquals("low confidence intent", result.fallbackReason)
    }

    @Test
    fun `allowed candidates include revert and skip invalid types`() {
        assertEquals("revert", scorer.score(CommitIntent(listOf("invalid", "revert"), intent = "restore previous guide", confidence = .9),
            diff("docs", path = "README.md", extension = "md"), .55).finalType)
        assertEquals("chore", scorer.score(CommitIntent(listOf("invalid"), intent = "update dependency lock", confidence = .9), diff("deps"), .55).finalType)
    }

    @Test
    fun `breaking evidence is explicit and survives conservative scoring`() {
        val defaults = CommitIntent(intent = "remove legacy endpoint")
        assertEquals(false, defaults.isBreaking)
        assertEquals("", defaults.breakingChange)
        val result = scorer.score(defaults.copy(isBreaking = true, breakingChange = "Use the v2 endpoint", confidence = .2), diff("api"), .55)
        assertEquals(true, result.isBreaking)
        assertEquals("Use the v2 endpoint", result.breakingChange)
        assertFailsWith<IllegalArgumentException> {
            scorer.score(defaults.copy(breakingChange = "Use v2"), diff("api"), .55)
        }
        assertEquals(false, scorer.score(defaults, diff("api"), .55).isBreaking)
    }

    @Test
    fun `missing summaries do not force document type for an incomplete selection`() {
        val incomplete = diff("docs", path = "docs/README.md", extension = "md").copy(totalFiles = 2, truncated = true)
        val result = scorer.score(CommitIntent(listOf("feat"), intent = "支持会话刷新", confidence = .9), incomplete, .55)
        assertEquals("feat", result.finalType)
    }

    @Test
    fun `empty and unknown semantic intent is rejected`() {
        for (text in listOf("", "  ", "unknown", "update code", "modify files", "optimize logic")) {
            assertFailsWith<IllegalArgumentException> { scorer.score(CommitIntent(intent = text), diff("auth"), .55) }
        }
    }
}
