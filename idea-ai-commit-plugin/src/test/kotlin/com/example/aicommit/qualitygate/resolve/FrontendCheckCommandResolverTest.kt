package com.example.aicommit.qualitygate.resolve

import com.example.aicommit.qualitygate.config.FrontendProjectCheckConfig
import com.example.aicommit.settings.AiCommitSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrontendCheckCommandResolverTest {

    @Test
    fun `resolve should auto-detect scripts from packageJson`() {
        val settings = mockk<AiCommitSettingsService>()
        every { settings.findFrontendProjectConfig("/repo/web") } returns null

        val resolver = FrontendCheckCommandResolver(settings)
        val result = resolver.resolve(
            projectRoot = "/repo/web",
            packageScripts = mapOf("ts:check" to "tsc --noEmit", "lint:eslint" to "eslint .")
        )

        assertEquals("pnpm ts:check", result.typescriptCommand)
        assertEquals("pnpm lint:eslint", result.eslintCommand)
        assertTrue(result.customCommands.isEmpty())
    }

    @Test
    fun `resolve should fallback to project config when script missing`() {
        val settings = mockk<AiCommitSettingsService>()
        every { settings.findFrontendProjectConfig("/repo/web") } returns FrontendProjectCheckConfig(
            projectRoot = "/repo/web",
            typescriptCommand = "pnpm ts:check",
            eslintCommand = "pnpm lint:eslint",
            customCommands = mutableListOf("pnpm lint:style")
        )

        val resolver = FrontendCheckCommandResolver(settings)
        val result = resolver.resolve(
            projectRoot = "/repo/web",
            packageScripts = emptyMap()
        )

        assertEquals("pnpm ts:check", result.typescriptCommand)
        assertEquals("pnpm lint:eslint", result.eslintCommand)
        assertEquals(listOf("pnpm lint:style"), result.customCommands)
    }

    @Test
    fun `resolve should return nulls when no scripts and no config`() {
        val settings = mockk<AiCommitSettingsService>()
        every { settings.findFrontendProjectConfig("/repo/web") } returns null

        val resolver = FrontendCheckCommandResolver(settings)
        val result = resolver.resolve(
            projectRoot = "/repo/web",
            packageScripts = emptyMap()
        )

        assertNull(result.typescriptCommand)
        assertNull(result.eslintCommand)
        assertTrue(result.customCommands.isEmpty())
    }

    @Test
    fun `resolve should prefer auto-detected over config`() {
        val settings = mockk<AiCommitSettingsService>()
        every { settings.findFrontendProjectConfig("/repo/web") } returns FrontendProjectCheckConfig(
            projectRoot = "/repo/web",
            typescriptCommand = "npx tsc",
            eslintCommand = "npx eslint"
        )

        val resolver = FrontendCheckCommandResolver(settings)
        val result = resolver.resolve(
            projectRoot = "/repo/web",
            packageScripts = mapOf("typecheck" to "tsc --noEmit")
        )

        // 自动识别优先
        assertEquals("pnpm typecheck", result.typescriptCommand)
        // eslint 未在 scripts 中找到，回退到 config
        assertEquals("npx eslint", result.eslintCommand)
    }

    @Test
    fun `resolve should filter blank custom commands`() {
        val settings = mockk<AiCommitSettingsService>()
        every { settings.findFrontendProjectConfig("/repo/web") } returns FrontendProjectCheckConfig(
            projectRoot = "/repo/web",
            customCommands = mutableListOf("pnpm lint", "", "  ")
        )

        val resolver = FrontendCheckCommandResolver(settings)
        val result = resolver.resolve("/repo/web", emptyMap())

        assertEquals(listOf("pnpm lint"), result.customCommands)
    }
}
