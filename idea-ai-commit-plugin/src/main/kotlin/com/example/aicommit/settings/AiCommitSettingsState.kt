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
    var readTimeoutMillis: Int = 60_000,
    // 两阶段生成相关配置，默认值安全可用，老配置自动兼容
    var analysisPromptTemplate: String = CommitPromptBuilder.ANALYSIS_TEMPLATE,
    var composerPromptTemplate: String = CommitPromptBuilder.COMPOSER_TEMPLATE,
    var analysisModel: String = "deepseek-chat",
    var composerModel: String = "deepseek-chat",
    var confidenceThreshold: Double = 0.55,
    var maxFiles: Int = 12,
    var maxHunksPerFile: Int = 3
)
