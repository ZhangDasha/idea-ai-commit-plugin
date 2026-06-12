package com.example.aicommit.qualitygate.run

import com.example.aicommit.qualitygate.model.FrontendCheckStatus
import com.example.aicommit.qualitygate.model.FrontendCheckType
import com.example.aicommit.qualitygate.runtime.FrontendCheckRuntimeService
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrontendCheckRunnerTest {

    /** 测试用的假命令启动器 */
    private class FakeCommandLauncher(
        private val exitCode: Int = 0,
        private val stdout: String = "",
        private val stderr: String = "",
        private val timedOut: Boolean = false,
        private val durationMillis: Long = 100
    ) : CommandLauncher {
        override fun execute(projectRoot: String, command: String, timeoutMillis: Long): CommandExecutionResult {
            return CommandExecutionResult(exitCode, stdout, stderr, timedOut, durationMillis)
        }
    }

    @Test
    fun `run should truncate details and release runtime permit`() {
        val runtime = mockk<FrontendCheckRuntimeService>(relaxed = true)
        val launcher = FakeCommandLauncher(
            exitCode = 1,
            stdout = "x".repeat(70 * 1024),
            stderr = "Type error"
        )
        val runner = FrontendCheckRunner(runtime, launcher)

        val result = runner.run(
            projectRoot = "/repo/web",
            type = FrontendCheckType.TYPESCRIPT,
            command = "pnpm ts:check",
            timeoutMillis = 20_000L
        )

        assertEquals(FrontendCheckStatus.CRITICAL, result.status)
        assertTrue(result.details.length <= 64 * 1024 + 32)
        verify { runtime.acquire("/repo/web") }
        verify { runtime.release("/repo/web") }
    }

    @Test
    fun `run should return PASSED on exit code 0`() {
        val runtime = mockk<FrontendCheckRuntimeService>(relaxed = true)
        val launcher = FakeCommandLauncher(exitCode = 0, stdout = "No errors")
        val runner = FrontendCheckRunner(runtime, launcher)

        val result = runner.run("/repo/web", FrontendCheckType.ESLINT, "pnpm lint:eslint", 20_000)

        assertEquals(FrontendCheckStatus.PASSED, result.status)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `run should return WARNING on timeout`() {
        val runtime = mockk<FrontendCheckRuntimeService>(relaxed = true)
        val launcher = FakeCommandLauncher(timedOut = true, durationMillis = 20_000)
        val runner = FrontendCheckRunner(runtime, launcher)

        val result = runner.run("/repo/web", FrontendCheckType.TYPESCRIPT, "pnpm ts:check", 20_000)

        assertEquals(FrontendCheckStatus.WARNING, result.status)
    }

    @Test
    fun `run should return WARNING for custom command failure`() {
        val runtime = mockk<FrontendCheckRuntimeService>(relaxed = true)
        val launcher = FakeCommandLauncher(exitCode = 1, stderr = "style error")
        val runner = FrontendCheckRunner(runtime, launcher)

        val result = runner.run("/repo/web", FrontendCheckType.CUSTOM, "pnpm lint:style", 20_000)

        assertEquals(FrontendCheckStatus.WARNING, result.status)
    }

    @Test
    fun `run should extract first useful line as summary`() {
        val runtime = mockk<FrontendCheckRuntimeService>(relaxed = true)
        val launcher = FakeCommandLauncher(
            exitCode = 1,
            stdout = "\n\nActual error line\nMore output",
            stderr = ""
        )
        val runner = FrontendCheckRunner(runtime, launcher)

        val result = runner.run("/repo/web", FrontendCheckType.ESLINT, "pnpm lint", 20_000)

        assertEquals("Actual error line", result.summary)
    }
}
