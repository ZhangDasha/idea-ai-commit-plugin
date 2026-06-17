package com.example.aicommit.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.application.ReadAction
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * 设置页 UI：Settings -> Tools -> AI Commit
 * 含两阶段生成相关配置：Analysis/Composer Model、Confidence Threshold、Max Files、Max Hunks。
 */
class AiCommitSettingsConfigurable : Configurable {

    private val settingsService = service<AiCommitSettingsService>()
    private val apiKeyField = JBPasswordField()
    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val analysisModelField = JBTextField()
    private val composerModelField = JBTextField()
    private val languageField = javax.swing.JComboBox(arrayOf("Chinese", "English"))
    private val confidenceThresholdField = JBTextField()
    private val maxFilesField = JBTextField()
    private val maxHunksPerFileField = JBTextField()
    private val promptArea = JBTextArea(8, 40)

    override fun getDisplayName(): String = "AI Commit"

    override fun createComponent(): JComponent = panel {
        row("Base URL") { cell(baseUrlField).align(AlignX.FILL) }
        row("API Key") { cell(apiKeyField).align(AlignX.FILL) }
        row("Model") { cell(modelField).align(AlignX.FILL) }
        row("Analysis Model") { cell(analysisModelField).align(AlignX.FILL) }
        row("Composer Model") { cell(composerModelField).align(AlignX.FILL) }
        row("Language") { cell(languageField) }
        row("Confidence Threshold (0-1)") { cell(confidenceThresholdField) }
        row("Max Files") { cell(maxFilesField) }
        row("Max Hunks / File") { cell(maxHunksPerFileField) }
        row("Prompt Template") { scrollCell(promptArea).align(AlignX.FILL) }
    }

    override fun reset() {
        val state = settingsService.state
        baseUrlField.text = state.baseUrl
        modelField.text = state.model
        analysisModelField.text = state.analysisModel
        composerModelField.text = state.composerModel
        languageField.model = DefaultComboBoxModel(arrayOf("Chinese", "English"))
        languageField.selectedItem = state.language
        confidenceThresholdField.text = state.confidenceThreshold.toString()
        maxFilesField.text = state.maxFiles.toString()
        maxHunksPerFileField.text = state.maxHunksPerFile.toString()
        promptArea.text = state.promptTemplate
        // PasswordSafe 读取可能触发 SlowOperations，用 ReadAction 包裹
        apiKeyField.text = ReadAction.compute<String, Exception> { settingsService.getApiKey() }
    }

    override fun isModified(): Boolean {
        val state = settingsService.state
        val savedKey = ReadAction.compute<String, Exception> { settingsService.getApiKey() }
        return baseUrlField.text != state.baseUrl
                || modelField.text != state.model
                || analysisModelField.text != state.analysisModel
                || composerModelField.text != state.composerModel
                || languageField.selectedItem != state.language
                || confidenceThresholdField.text != state.confidenceThreshold.toString()
                || maxFilesField.text != state.maxFiles.toString()
                || maxHunksPerFileField.text != state.maxHunksPerFile.toString()
                || promptArea.text != state.promptTemplate
                || String(apiKeyField.password) != savedKey
    }

    override fun apply() {
        val state = settingsService.state
        state.baseUrl = baseUrlField.text
        state.model = modelField.text
        state.analysisModel = analysisModelField.text
        state.composerModel = composerModelField.text
        state.language = languageField.selectedItem as String
        state.confidenceThreshold = confidenceThresholdField.text.trim().toDoubleOrNull() ?: state.confidenceThreshold
        state.maxFiles = maxFilesField.text.trim().toIntOrNull() ?: state.maxFiles
        state.maxHunksPerFile = maxHunksPerFileField.text.trim().toIntOrNull() ?: state.maxHunksPerFile
        state.promptTemplate = promptArea.text
        settingsService.setApiKey(String(apiKeyField.password))
    }
}
