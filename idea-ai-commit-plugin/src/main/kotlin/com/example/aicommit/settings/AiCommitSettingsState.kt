package com.example.aicommit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.example.aicommit.prompt.CommitPromptBuilder

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
    var readTimeoutMillis: Int = 60_000
)
