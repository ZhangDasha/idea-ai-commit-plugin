package com.example.aicommit.qualitygate.resolve

import com.example.aicommit.settings.AiCommitSettingsService

/**
 * 命令解析后的结果
 */
data class ResolvedFrontendCommands(
    val typescriptCommand: String?,
    val eslintCommand: String?,
    val customCommands: List<String>
)

/**
 * 前端检查命令解析器
 *
 * 解析策略：
 * 1. 优先从 package.json scripts 中自动识别
 * 2. 回退到插件设置中的项目级手动配置
 */
class FrontendCheckCommandResolver(
    private val settingsService: AiCommitSettingsService
) {

    // TypeScript 常见脚本名，按优先级排列
    private val typescriptScriptNames = listOf("ts:check", "typecheck", "tsc")
    // ESLint 常见脚本名，按优先级排列
    private val eslintScriptNames = listOf("lint:eslint", "eslint", "lint")

    /**
     * 解析项目的前端检查命令
     * @param projectRoot 项目根目录
     * @param packageScripts package.json 中的 scripts 映射
     */
    fun resolve(projectRoot: String, packageScripts: Map<String, String>): ResolvedFrontendCommands {
        val config = settingsService.findFrontendProjectConfig(projectRoot)

        val typescript = findScript(packageScripts, typescriptScriptNames)
            ?: config?.typescriptCommand

        val eslint = findScript(packageScripts, eslintScriptNames)
            ?: config?.eslintCommand

        return ResolvedFrontendCommands(
            typescriptCommand = typescript,
            eslintCommand = eslint,
            customCommands = config?.customCommands?.filter { it.isNotBlank() }.orEmpty()
        )
    }

    /** 从 scripts 中查找第一个匹配的脚本名，返回 pnpm <script> */
    private fun findScript(scripts: Map<String, String>, names: List<String>): String? {
        val matched = names.firstOrNull { scripts.containsKey(it) } ?: return null
        return "pnpm $matched"
    }
}
