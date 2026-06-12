package com.example.aicommit.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.application.ReadAction
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * 设置页 UI：Settings -> Tools -> AI Commit
 */
class AiCommitSettingsConfigurable : Configurable {

    private val settingsService = service<AiCommitSettingsService>()
    private val apiKeyField = JBPasswordField()
    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val languageField = javax.swing.JComboBox(arrayOf("Chinese", "English"))
    private val promptArea = JBTextArea(8, 40)
    // 前端检查相关控件
    private val frontendChecksEnabledBox = JBCheckBox("启用获取 AI Commit 前前端检查")
    private val frontendCheckTimeoutField = JBTextField()
    private val includeWarningSummaryBox = JBCheckBox("存在警告时将摘要附加到 Prompt")

    override fun getDisplayName(): String = "AI Commit"

    override fun createComponent(): JComponent = panel {
        row("Base URL") { cell(baseUrlField).align(AlignX.FILL) }
        row("API Key") { cell(apiKeyField).align(AlignX.FILL) }
        row("Model") { cell(modelField).align(AlignX.FILL) }
        row("Language") { cell(languageField) }
        row("Prompt Template") { scrollCell(promptArea).align(AlignX.FILL) }
        group("Frontend Checks") {
            row { cell(frontendChecksEnabledBox) }
            row("Timeout(ms)") { cell(frontendCheckTimeoutField).align(AlignX.FILL) }
            row { cell(includeWarningSummaryBox) }
        }
    }

    override fun reset() {
        val state = settingsService.state
        baseUrlField.text = state.baseUrl
        modelField.text = state.model
        languageField.model = DefaultComboBoxModel(arrayOf("Chinese", "English"))
        languageField.selectedItem = state.language
        promptArea.text = state.promptTemplate
        apiKeyField.text = ReadAction.compute<String, Exception> { settingsService.getApiKey() }
        // 前端检查设置回填
        frontendChecksEnabledBox.isSelected = state.frontendChecksEnabled
        frontendCheckTimeoutField.text = state.frontendCheckTimeoutMillis.toString()
        includeWarningSummaryBox.isSelected = state.includeWarningSummaryInPrompt
    }

    override fun isModified(): Boolean {
        val state = settingsService.state
        val savedKey = ReadAction.compute<String, Exception> { settingsService.getApiKey() }
        return baseUrlField.text != state.baseUrl
                || modelField.text != state.model
                || languageField.selectedItem != state.language
                || promptArea.text != state.promptTemplate
                || String(apiKeyField.password) != savedKey
                || frontendChecksEnabledBox.isSelected != state.frontendChecksEnabled
                || frontendCheckTimeoutField.text != state.frontendCheckTimeoutMillis.toString()
                || includeWarningSummaryBox.isSelected != state.includeWarningSummaryInPrompt
    }

    override fun apply() {
        val state = settingsService.state
        state.baseUrl = baseUrlField.text
        state.model = modelField.text
        state.language = languageField.selectedItem as String
        state.promptTemplate = promptArea.text
        settingsService.setApiKey(String(apiKeyField.password))
        // 保存前端检查设置
        state.frontendChecksEnabled = frontendChecksEnabledBox.isSelected
        state.frontendCheckTimeoutMillis = frontendCheckTimeoutField.text.toIntOrNull() ?: 20_000
        state.includeWarningSummaryInPrompt = includeWarningSummaryBox.isSelected
    }
}
