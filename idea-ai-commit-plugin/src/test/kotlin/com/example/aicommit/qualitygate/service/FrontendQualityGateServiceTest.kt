package com.example.aicommit.qualitygate.service

import com.example.aicommit.qualitygate.detect.FrontendProjectDetector
import com.example.aicommit.qualitygate.model.*
import com.example.aicommit.qualitygate.resolve.FrontendCheckCommandResolver
import com.example.aicommit.qualitygate.run.CommandExecutionResult
import com.example.aicommit.qualitygate.run.CommandLauncher
import com.example.aicommit.qualitygate.run.FrontendCheckRunner
import com.example.aicommit.qualitygate.runtime.FrontendCheckRuntimeService
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontendQualityGateServiceTest {

    private fun fakeDetector(vararg roots: String) = mockk<FrontendProjectDetector>().also {
        every { it.detectProjectRoots(any()) } returns roots.toList()
    }

    private fun fakeResolver(
        typescript: String?,
        eslint: String?,
        custom: List<String> = emptyList()
    ) = object : FrontendCheckCommandResolver(mockk(relaxed = true)) {
        override fun resolve(projectRoot: String, packageScripts: Map<String, String>) =
            com.example.aicommit.qualitygate.resolve.ResolvedFrontendCommands(
                typescriptCommand = typescript,
                eslintCommand = eslint,
                customCommands = custom
            )
    }

    /** 按顺序返回检查结果的假 Runner */
    private class FakeRunner(
        private vararg val results: FrontendCheckResult
    ) : FrontendCheckRunner(
        mockk<FrontendCheckRuntimeService>(relaxed = true),
        object : CommandLauncher {
            override fun execute(projectRoot: String, command: String, timeoutMillis: Long) =
                CommandExecutionResult(0, "", "", false, 10)
        }
    ) {
        private var index = 0
        override fun run(projectRoot: String, type: FrontendCheckType, command: String, timeoutMillis: Long): FrontendCheckResult {
            return results[index++]
        }
    }

    @Test
    fun `evaluate should return empty when no frontend projects`() {
        val service = FrontendQualityGateService(
            detector = fakeDetector(), // 无项目
            resolver = fakeResolver(null, null),
            runner = FakeRunner()
        )
        val result = service.evaluate(mockk<Project>(), listOf("/repo/server/App.java"))
        assertFalse(result.hasCritical)
        assertFalse(result.hasWarnings)
        assertFalse(result.requiresConfirmation)
    }

    @Test
    fun `evaluate should build prompt warning summary only when warning exists`() {
        val service = FrontendQualityGateService(
            detector = fakeDetector("/repo/web"),
            resolver = fakeResolver(typescript = null, eslint = "pnpm lint:eslint", custom = listOf("pnpm lint:style")),
            runner = FakeRunner(
                // ESLint 通过（typescript=null 不经过 runner，直接创建 WARNING）
                FrontendCheckResult(FrontendCheckType.ESLINT, "pnpm lint:eslint", FrontendCheckStatus.PASSED, 0, 10, "ok", "ok"),
                // 自定义命令失败 → WARNING
                FrontendCheckResult(FrontendCheckType.CUSTOM, "pnpm lint:style", FrontendCheckStatus.WARNING, 1, 12, "style failed", "style failed")
            )
        )

        val result = service.evaluate(project = mockk(), changedPaths = listOf("/repo/web/src/App.vue"))

        assertFalse(result.hasCritical)
        assertTrue(result.hasWarnings)
        // 验证 warning summary 包含所有警告信息
        val summary = result.warningSummaryForPrompt ?: throw AssertionError("warningSummaryForPrompt is null")
        assertTrue(summary.contains("未识别到 TypeScript 检查命令"), "Expected TS warning in: $summary")
        assertTrue(summary.contains("style failed"), "Expected style warning in: $summary")
    }

    @Test
    fun `evaluate should set requiresConfirmation when critical exists`() {
        val service = FrontendQualityGateService(
            detector = fakeDetector("/repo/web"),
            resolver = fakeResolver("pnpm ts:check", "pnpm lint:eslint"),
            runner = FakeRunner(
                // TypeScript 失败 → CRITICAL
                FrontendCheckResult(FrontendCheckType.TYPESCRIPT, "pnpm ts:check", FrontendCheckStatus.CRITICAL, 1, 50, "TS error", "type error"),
                // ESLint 通过
                FrontendCheckResult(FrontendCheckType.ESLINT, "pnpm lint:eslint", FrontendCheckStatus.PASSED, 0, 10, "ok", "ok")
            )
        )

        val result = service.evaluate(project = mockk(), changedPaths = listOf("/repo/web/src/App.tsx"))

        assertTrue(result.hasCritical)
        assertTrue(result.requiresConfirmation)
    }

    @Test
    fun `evaluate should pass all checks`() {
        val service = FrontendQualityGateService(
            detector = fakeDetector("/repo/web"),
            resolver = fakeResolver("pnpm ts:check", "pnpm lint:eslint"),
            runner = FakeRunner(
                FrontendCheckResult(FrontendCheckType.TYPESCRIPT, "pnpm ts:check", FrontendCheckStatus.PASSED, 0, 50, "ok", "ok"),
                FrontendCheckResult(FrontendCheckType.ESLINT, "pnpm lint:eslint", FrontendCheckStatus.PASSED, 0, 10, "ok", "ok")
            )
        )

        val result = service.evaluate(project = mockk(), changedPaths = listOf("/repo/web/src/App.tsx"))

        assertFalse(result.hasCritical)
        assertFalse(result.hasWarnings)
        assertFalse(result.requiresConfirmation)
        assertEquals(null, result.warningSummaryForPrompt)
    }
}
