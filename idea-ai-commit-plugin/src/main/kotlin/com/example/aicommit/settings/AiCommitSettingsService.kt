package com.example.aicommit.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 配置服务：管理 API Key 安全存储 + 非敏感配置持久化 + 校验
 */
@State(
    name = "com.example.aicommit.settings.AiCommitSettingsService",
    storages = [Storage("aicommit-settings.xml")]
)
@Service(Service.Level.APP)
class AiCommitSettingsService : PersistentStateComponent<AiCommitSettingsState> {

    companion object {
        private val CREDENTIAL_ATTRIBUTES = CredentialAttributes(generateServiceName("AI Commit", "api_key"))
    }

    private var state = AiCommitSettingsState()

    override fun getState(): AiCommitSettingsState = state

    override fun loadState(state: AiCommitSettingsState) {
        this.state = state
    }

    /** 从 PasswordSafe 读取 API Key */
    fun getApiKey(): String =
        PasswordSafe.instance.getPassword(CREDENTIAL_ATTRIBUTES).orEmpty()

    /** 写入 API Key 到 PasswordSafe */
    fun setApiKey(apiKey: String) {
        PasswordSafe.instance.setPassword(CREDENTIAL_ATTRIBUTES, apiKey)
    }

    /** 校验配置完整性，返回 null 表示合法，否则返回错误描述 */
    fun validate(state: AiCommitSettingsState, apiKey: String): String? {
        if (state.baseUrl.isBlank()) return "Base URL 不能为空"
        if (apiKey.isBlank()) return "API Key 不能为空"
        if (state.model.isBlank()) return "Model 不能为空"
        // 两阶段生成新字段校验
        if (state.analysisModel.isBlank()) return "Analysis Model 不能为空"
        if (state.composerModel.isBlank()) return "Composer Model 不能为空"
        if (state.confidenceThreshold !in 0.0..1.0) return "Confidence Threshold 必须在 0 到 1 之间"
        if (state.maxFiles <= 0) return "Max Files 必须大于 0"
        if (state.maxHunksPerFile <= 0) return "Max Hunks Per File 必须大于 0"
        if (!state.promptTemplate.contains("{diff}")) return "Prompt 必须包含 {diff}"
        return null
    }
}
