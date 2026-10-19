package com.example.aicommit.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Settings -> Tools -> AI Commit. Only fields used by the two-stage pipeline are exposed. */
class AiCommitSettingsConfigurable : Configurable {
    private val settingsService = service<AiCommitSettingsService>()
    private val apiKeyField = JBPasswordField()
    private val baseUrlField = JBTextField()
    private val analysisModelField = JBTextField()
    private val composerModelField = JBTextField()
    private val languageField = javax.swing.JComboBox(arrayOf("Chinese", "English"))
    private val confidenceField = JBTextField()
    private val maxFilesField = JBTextField()
    private val maxHunksField = JBTextField()
    private val connectTimeoutField = JBTextField()
    private val readTimeoutField = JBTextField()
    private val analysisPromptArea = JBTextArea(10, 60)
    private val composerPromptArea = JBTextArea(10, 60)

    override fun getDisplayName(): String = "AI Commit"

    override fun createComponent(): JComponent = panel {
        row("Base URL") {
            cell(baseUrlField).align(AlignX.FILL).comment("支持服务根地址、/v1 或完整的 /chat/completions 地址")
        }
        row("API Key") { cell(apiKeyField).align(AlignX.FILL).comment("留空保留已保存的密钥") }
        row("Analysis Model") {
            cell(analysisModelField).align(AlignX.FILL).comment("填写 API 模型 ID；DeepSeek Flash 为 deepseek-flash，展示名称可能不同")
        }
        row("Composer Model") {
            cell(composerModelField).align(AlignX.FILL).comment("使用接口 /models 返回的模型 ID")
        }
        row("Language") { cell(languageField) }
        row("Confidence Threshold (0–1)") { cell(confidenceField) }
        row("Max Files with Hunks") { cell(maxFilesField) }
        row("Max Hunks / File") { cell(maxHunksField) }
        row("Connect Timeout (ms)") { cell(connectTimeoutField) }
        row("Read Timeout (ms)") { cell(readTimeoutField) }
        row("Analysis Prompt") { scrollCell(analysisPromptArea).align(AlignX.FILL).comment("使用 {structured_diff} 和 {language}") }
        row("Composer Prompt") { scrollCell(composerPromptArea).align(AlignX.FILL).comment("使用 {intent} 和 {language}") }
    }

    override fun reset() {
        val state = settingsService.state
        baseUrlField.text = state.baseUrl
        analysisModelField.text = state.analysisModel
        composerModelField.text = state.composerModel
        languageField.selectedItem = state.language
        confidenceField.text = state.confidenceThreshold.toString()
        maxFilesField.text = state.maxFiles.toString()
        maxHunksField.text = state.maxHunksPerFile.toString()
        connectTimeoutField.text = state.connectTimeoutMillis.toString()
        readTimeoutField.text = state.readTimeoutMillis.toString()
        analysisPromptArea.text = state.analysisPromptTemplate
        composerPromptArea.text = state.composerPromptTemplate
        apiKeyField.text = ""
    }

    override fun isModified(): Boolean {
        val state = settingsService.state
        return baseUrlField.text != state.baseUrl || analysisModelField.text != state.analysisModel ||
            composerModelField.text != state.composerModel || languageField.selectedItem != state.language ||
            confidenceField.text != state.confidenceThreshold.toString() || maxFilesField.text != state.maxFiles.toString() ||
            maxHunksField.text != state.maxHunksPerFile.toString() ||
            connectTimeoutField.text != state.connectTimeoutMillis.toString() ||
            readTimeoutField.text != state.readTimeoutMillis.toString() ||
            analysisPromptArea.text != state.analysisPromptTemplate || composerPromptArea.text != state.composerPromptTemplate ||
            apiKeyField.password.isNotEmpty()
    }

    override fun apply() {
        fun number(field: JBTextField, label: String): Int = field.text.trim().toIntOrNull()
            ?: throw ConfigurationException("$label 必须为整数")
        val candidate = settingsService.state.copy(
            baseUrl = baseUrlField.text.trim(),
            analysisModel = analysisModelField.text.trim(),
            composerModel = composerModelField.text.trim(),
            language = languageField.selectedItem as String,
            confidenceThreshold = confidenceField.text.trim().toDoubleOrNull()
                ?: throw ConfigurationException("Confidence Threshold 必须为数字"),
            maxFiles = number(maxFilesField, "Max Files"),
            maxHunksPerFile = number(maxHunksField, "Max Hunks / File"),
            connectTimeoutMillis = number(connectTimeoutField, "Connect Timeout"),
            readTimeoutMillis = number(readTimeoutField, "Read Timeout"),
            analysisPromptTemplate = analysisPromptArea.text,
            composerPromptTemplate = composerPromptArea.text
        )
        val password = apiKeyField.password
        try {
            val key = String(password)
            // Existing credentials are checked in the background when generation starts.
            settingsService.validate(candidate, key.ifBlank { "keep existing credential" })?.let {
                throw ConfigurationException(it)
            }
            if (key.isNotBlank()) settingsService.setApiKey(key)
            settingsService.updateState(candidate)
            apiKeyField.text = ""
        } finally {
            password.fill('\u0000')
        }
    }
}
