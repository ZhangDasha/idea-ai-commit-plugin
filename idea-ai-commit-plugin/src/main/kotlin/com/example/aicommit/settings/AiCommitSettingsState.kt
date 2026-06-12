package com.example.aicommit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.example.aicommit.prompt.CommitPromptBuilder
import com.example.aicommit.qualitygate.config.FrontendProjectCheckConfig

/**
 * 插件配置状态对象，持久化到 ai-commit.xml
 */
@State(name = "AiCommitSettingsState", storages = [Storage("ai-commit.xml")])
data class AiCommitSettingsState(
    var baseUrl: String = "https://api.deepseek.com/chat/completions",
    var model: String = "deepseek-chat",
    var language: String = "Chinese",
    var promptTemplate: String = CommitPromptBuilder.DEFAULT_TEMPLATE,
    var connectTimeoutMillis: Int = 10_000,
    var readTimeoutMillis: Int = 60_000,
    // 前端检查总开关
    var frontendChecksEnabled: Boolean = true,
    // 所有前端检查共享一个总超时
    var frontendCheckTimeoutMillis: Int = 20_000,
    // 仅存在 warning 时附加到 prompt
    var includeWarningSummaryInPrompt: Boolean = true,
    // 按项目根目录保存手动补充命令
    var frontendProjectConfigs: MutableList<FrontendProjectCheckConfig> = mutableListOf()
)
