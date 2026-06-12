package com.example.aicommit.qualitygate.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import java.io.File

/**
 * 命令启动器接口，便于测试注入
 */
interface CommandLauncher {
    fun execute(projectRoot: String, command: String, timeoutMillis: Long): CommandExecutionResult
}

/**
 * 基于 IntelliJ CapturingProcessHandler 的命令执行器
 * 通过 zsh -lc 执行命令，支持管道和 shell 特性
 */
class OsCommandLauncher : CommandLauncher {

    override fun execute(projectRoot: String, command: String, timeoutMillis: Long): CommandExecutionResult {
        val commandLine = GeneralCommandLine("zsh", "-lc", command)
            .withWorkDirectory(File(projectRoot))

        val start = System.currentTimeMillis()
        val processHandler = CapturingProcessHandler(commandLine)
        // 超时单位：毫秒
        val output: ProcessOutput = processHandler.runProcess(timeoutMillis.toInt(), true)

        val timedOut = output.isTimeout
        return CommandExecutionResult(
            exitCode = if (timedOut) null else output.exitCode,
            stdout = output.stdout,
            stderr = output.stderr,
            timedOut = timedOut,
            durationMillis = System.currentTimeMillis() - start
        )
    }
}
