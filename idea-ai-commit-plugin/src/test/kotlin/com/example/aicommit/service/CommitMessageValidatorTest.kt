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
    fun `validator accepts body and footer separated from header`() {
        val intent = ScoredCommitIntent("fix", null, "repair session expiry", "Handle expired sessions before loading data", "", .9)
        val message = "fix: repair session expiry\n\nHandle expired sessions before loading data.\n\nRefs: #42"
        assertEquals(message, validator.validateOrFallback(message, intent))
    }

    @Test
    fun `validator rejects mismatched type scope empty subject punctuation and oversized header`() {
        val intent = ScoredCommitIntent("fix", "auth", "repair session expiry", "", "", .9)
        for (message in listOf("feat(auth): repair session expiry", "fix: repair session expiry",
            "fix(ui): repair session expiry", "fix(auth): ", "fix(auth): repair session expiry.",
            "fix(auth): 修复会话过期。", "fix(auth): 修复会话过期…", "fix(auth): Explanation: repaired expiry",
            "fix(auth): ${"x".repeat(62)}", "fix(auth): repair expiry\nExplanation: done",
            "fix(auth): repair expiry\n\n~~~kotlin\nx\n~~~")) {
            assertFailsWith<IllegalArgumentException>(message) { validator.validateOrFallback(message, intent) }
        }
        val boundary = "fix(auth): ${"x".repeat(61)}"
        assertEquals(72, boundary.length)
        assertEquals(boundary, validator.validateOrFallback(boundary, intent))
    }

    @Test
    fun `fallback keeps original language and removes trailing punctuation`() {
        val intent = ScoredCommitIntent("fix", null, "修复会话过期。", "", "", .2)
        assertEquals("fix: 修复会话过期", validator.buildFallback(intent))
        val longIntent = intent.copy(normalizedIntent = "修".repeat(100))
        val fallback = validator.buildFallback(longIntent)
        assertEquals(72, fallback.length)
        assertEquals(fallback, validator.validateOrFallback(fallback, longIntent))
    }

    @Test
    fun `unknown intent and invalid fallback metadata are rejected`() {
        for (text in listOf("", " ", "unknown", "Unknown.", "update code")) {
            val intent = ScoredCommitIntent("fix", null, text, "", "", .9)
            assertFailsWith<IllegalArgumentException> { validator.buildFallback(intent) }
            assertFailsWith<IllegalArgumentException> { validator.validateOrFallback("fix: repair expiry", intent) }
        }
        for (intent in listOf(ScoredCommitIntent("invalid", null, "repair expiry", "", "", .9),
            ScoredCommitIntent("fix", "bad scope", "repair expiry", "", "", .9))) {
            assertFailsWith<IllegalArgumentException> { validator.buildFallback(intent) }
        }
    }

    @Test
    fun `breaking headers and footers are supported only for explicit evidence`() {
        val breaking = ScoredCommitIntent("feat", "api", "remove legacy endpoint", "", "", .9,
            isBreaking = true, breakingChange = "Use the v2 endpoint")
        val fallback = "feat(api)!: remove legacy endpoint\n\nBREAKING CHANGE: Use the v2 endpoint"
        assertEquals(fallback, validator.buildFallback(breaking))
        assertEquals(fallback, validator.validateOrFallback(fallback, breaking))
        val footerOnly = "feat(api): remove legacy endpoint\n\nBREAKING CHANGE: Use the v2 endpoint"
        assertEquals(footerOnly, validator.validateOrFallback(footerOnly, breaking))
        val hyphenFooter = footerOnly.replace("BREAKING CHANGE", "BREAKING-CHANGE")
        assertEquals(hyphenFooter, validator.validateOrFallback(hyphenFooter, breaking))
        val adjacentFooters = footerOnly.replace("\n\nBREAKING CHANGE", "\n\nRefs: #42\nBREAKING CHANGE")
        assertEquals(adjacentFooters, validator.validateOrFallback(adjacentFooters, breaking))
        val bangOnly = "feat(api)!: remove legacy endpoint"
        assertEquals(bangOnly, validator.validateOrFallback(bangOnly, breaking))
        assertEquals(bangOnly, validator.buildFallback(breaking.copy(breakingChange = "")))
        for (message in listOf("feat(api): remove legacy endpoint",
            "feat(api): remove legacy endpoint\n\nBREAKING CHANGE:",
            "feat(api): remove legacy endpoint\n\nBody\nBREAKING CHANGE: Use v2")) {
            assertFailsWith<IllegalArgumentException> { validator.validateOrFallback(message, breaking) }
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validateOrFallback(bangOnly, breaking.copy(isBreaking = false, breakingChange = ""))
        }
        assertFailsWith<IllegalArgumentException> { validator.buildFallback(breaking.copy(isBreaking = false)) }
    }

    @Test
    fun `revert and legacy constructor defaults remain valid`() {
        val intent = ScoredCommitIntent("revert", null, "restore previous parser", "", "", .9)
        assertEquals(false, intent.isBreaking)
        assertEquals("", intent.breakingChange)
        assertEquals("revert: restore previous parser", validator.buildFallback(intent))
    }

    @Test
    fun `body requires supporting intent and low confidence allows only breaking footer`() {
        val intent = ScoredCommitIntent("fix", null, "repair expiry", "", "", .9)
        assertFailsWith<IllegalArgumentException> {
            validator.validateOrFallback("fix: repair expiry\n\nInvented impact", intent)
        }
        val breaking = intent.copy(isBreaking = true, breakingChange = "Update existing clients", fallbackReason = "low confidence intent")
        val message = "fix!: repair expiry\n\nBREAKING CHANGE: Update existing clients"
        assertEquals(message, validator.validateOrFallback(message, breaking))
    }

    @Test
    fun `validator rejects markdown body and multiple commits`() {
        val intent = ScoredCommitIntent("fix", null, "repair expiry", "Prevent expired sessions", "", .9)
        for (body in listOf("# Explanation", "- item", "1. item", "feat: unrelated change")) {
            assertFailsWith<IllegalArgumentException> { validator.validateOrFallback("fix: repair expiry\n\n$body", intent) }
        }
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
