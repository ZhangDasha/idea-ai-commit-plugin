package com.example.aicommit.qualitygate.run

import com.example.aicommit.qualitygate.model.FrontendCheckResult
import com.example.aicommit.qualitygate.model.FrontendCheckStatus
import com.example.aicommit.qualitygate.model.FrontendCheckType
import com.example.aicommit.qualitygate.runtime.FrontendCheckRuntimeService

/**
 * 前端检查执行器：执行外部命令并生成检查结果
 *
 * 职责：
 * - 获取/释放运行时并发许可
 * - 截断输出防止内存溢出
 * - 根据退出码和检查类型判定严重级别
 */
open class FrontendCheckRunner(
    private val runtimeService: FrontendCheckRuntimeService,
    private val launcher: CommandLauncher = OsCommandLauncher()
) {

    companion object {
        private const val DETAILS_LIMIT = 64 * 1024
        private const val SUMMARY_LIMIT = 8 * 1024
    }

    /**
     * 执行单次前端检查
     * @param projectRoot 项目根目录
     * @param type 检查类型
     * @param command 要执行的命令
     * @param timeoutMillis 超时时间
     */
    open fun run(
        projectRoot: String,
        type: FrontendCheckType,
        command: String,
        timeoutMillis: Long
    ): FrontendCheckResult {
        runtimeService.acquire(projectRoot)
        try {
            val execution = launcher.execute(projectRoot, command, timeoutMillis)
            val details = truncate("${execution.stdout}\n${execution.stderr}", DETAILS_LIMIT)
            val summary = truncate(firstUsefulLine(execution), SUMMARY_LIMIT)
            val status = classifyStatus(type, execution)

            return FrontendCheckResult(
                type = type,
                command = command,
                status = status,
                exitCode = execution.exitCode,
                durationMillis = execution.durationMillis,
                summary = summary,
                details = details
            )
        } finally {
            runtimeService.release(projectRoot)
        }
    }

    /** 根据检查类型和执行结果判定严重级别 */
    private fun classifyStatus(type: FrontendCheckType, execution: CommandExecutionResult): FrontendCheckStatus {
        if (execution.timedOut) return FrontendCheckStatus.WARNING
        if (type == FrontendCheckType.CUSTOM && execution.exitCode != 0) return FrontendCheckStatus.WARNING
        if (execution.exitCode == 0) return FrontendCheckStatus.PASSED
        return FrontendCheckStatus.CRITICAL
    }

    /** 提取第一行有意义的输出作为摘要 */
    private fun firstUsefulLine(execution: CommandExecutionResult): String {
        return sequenceOf(execution.stderr, execution.stdout)
            .flatMap { it.lineSequence() }
            .firstOrNull { it.isNotBlank() }
            ?: "No output"
    }

    /** 截断输出，超出时追加标记 */
    private fun truncate(value: String, limit: Int): String {
        if (value.length <= limit) return value
        return value.take(limit) + "\n[output truncated]"
    }
}
