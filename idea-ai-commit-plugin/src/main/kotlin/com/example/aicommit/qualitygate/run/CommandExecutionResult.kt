package com.example.aicommit.qualitygate.run

/**
 * 外部命令执行结果
 * @param exitCode 进程退出码（超时时为 null）
 * @param stdout 标准输出
 * @param stderr 标准错误
 * @param timedOut 是否超时
 * @param durationMillis 执行耗时
 */
data class CommandExecutionResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMillis: Long
)
