package com.example.aicommit.qualitygate.runtime

/**
 * 受追踪的外部进程句柄，支持取消
 */
interface TrackedProcess {
    val projectRoot: String
    val commandLine: String
    fun cancel(reason: String)
}
