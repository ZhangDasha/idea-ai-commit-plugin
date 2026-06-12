package com.example.aicommit.qualitygate.service

import com.example.aicommit.qualitygate.detect.FrontendProjectDetector
import com.example.aicommit.qualitygate.model.*
import com.example.aicommit.qualitygate.resolve.FrontendCheckCommandResolver
import com.example.aicommit.qualitygate.run.FrontendCheckRunner
import com.intellij.openapi.project.Project
import java.io.File

/**
 * 前端质量闸口核心编排服务
 *
 * 职责：项目识别 → 命令解析 → 执行检查 → 结果汇总
 * 不直接操作 AI prompt 拼装，仅输出 FrontendCheckSessionResult
 */
class FrontendQualityGateService(
    private val detector: FrontendProjectDetector,
    private val resolver: FrontendCheckCommandResolver,
    private val runner: FrontendCheckRunner
) {

    /**
     * 执行质量闸口检查
     * @param project IntelliJ 项目
     * @param changedPaths 本次勾选变更的文件路径
     * @return 检查会话结果
     */
    fun evaluate(project: Project, changedPaths: List<String>): FrontendCheckSessionResult {
        val roots = detector.detectProjectRoots(changedPaths)
        if (roots.isEmpty()) {
            return emptyResult()
        }

        val projectResults = roots.map { root ->
            val scripts = readPackageScripts(root)
            val resolved = resolver.resolve(root, scripts)
            val checks = mutableListOf<FrontendCheckResult>()

            // TypeScript 检查
            if (resolved.typescriptCommand != null) {
                checks += runner.run(root, FrontendCheckType.TYPESCRIPT, resolved.typescriptCommand, 20_000)
            } else {
                checks += FrontendCheckResult(
                    FrontendCheckType.TYPESCRIPT, null, FrontendCheckStatus.WARNING,
                    null, 0, "未识别到 TypeScript 检查命令", ""
                )
            }

            // ESLint 检查
            if (resolved.eslintCommand != null) {
                checks += runner.run(root, FrontendCheckType.ESLINT, resolved.eslintCommand, 20_000)
            } else {
                checks += FrontendCheckResult(
                    FrontendCheckType.ESLINT, null, FrontendCheckStatus.WARNING,
                    null, 0, "未识别到 ESLint 检查命令", ""
                )
            }

            // 自定义命令检查
            resolved.customCommands.forEach { command ->
                checks += runner.run(root, FrontendCheckType.CUSTOM, command, 20_000)
            }

            FrontendProjectCheckResult(root, File(root).name, checks)
        }

        return buildSessionResult(projectResults)
    }

    /** 读取项目 package.json 中的 scripts */
    private fun readPackageScripts(projectRoot: String): Map<String, String> {
        val packageJson = File(projectRoot, "package.json")
        if (!packageJson.exists()) return emptyMap()
        return try {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val tree = mapper.readTree(packageJson)
            val scriptsNode = tree.path("scripts") ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            scriptsNode.fields().forEach { (key, value) ->
                result[key] = value.asText()
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 构建会话汇总结果 */
    private fun buildSessionResult(projectResults: List<FrontendProjectCheckResult>): FrontendCheckSessionResult {
        val allChecks = projectResults.flatMap { it.checks }
        val hasCritical = allChecks.any { it.status == FrontendCheckStatus.CRITICAL }
        val warnings = allChecks.filter { it.status == FrontendCheckStatus.WARNING }

        return FrontendCheckSessionResult(
            projects = projectResults,
            hasCritical = hasCritical,
            hasWarnings = warnings.isNotEmpty(),
            warningSummaryForPrompt = warnings.takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- ${it.summary}" },
            requiresConfirmation = hasCritical
        )
    }

    private fun emptyResult() = FrontendCheckSessionResult(
        projects = emptyList(), hasCritical = false, hasWarnings = false,
        warningSummaryForPrompt = null, requiresConfirmation = false
    )
}
