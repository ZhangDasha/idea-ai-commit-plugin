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
 */
class AiCommitSettingsConfigurable : Configurable {

    private val settingsService = service<AiCommitSettingsService>()
    private val apiKeyField = JBPasswordField()
    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val languageField = javax.swing.JComboBox(arrayOf("Chinese", "English"))
    private val promptArea = JBTextArea(8, 40)

    override fun getDisplayName(): String = "AI Commit"

    override fun createComponent(): JComponent = panel {
        row("Base URL") { cell(baseUrlField).align(AlignX.FILL) }
        row("API Key") { cell(apiKeyField).align(AlignX.FILL) }
        row("Model") { cell(modelField).align(AlignX.FILL) }
        row("Language") { cell(languageField) }
        row("Prompt Template") { scrollCell(promptArea).align(AlignX.FILL) }
    }

    override fun reset() {
        val state = settingsService.state
        baseUrlField.text = state.baseUrl
        modelField.text = state.model
        languageField.model = DefaultComboBoxModel(arrayOf("Chinese", "English"))
        languageField.selectedItem = state.language
        promptArea.text = state.promptTemplate
        // PasswordSafe 读取可能触发 SlowOperations，用 ReadAction 包裹
        apiKeyField.text = ReadAction.compute<String, Exception> { settingsService.getApiKey() }
    }

    override fun isModified(): Boolean {
        val state = settingsService.state
        val savedKey = ReadAction.compute<String, Exception> { settingsService.getApiKey() }
        return baseUrlField.text != state.baseUrl
                || modelField.text != state.model
                || languageField.selectedItem != state.language
                || promptArea.text != state.promptTemplate
                || String(apiKeyField.password) != savedKey
    }

    override fun apply() {
        val state = settingsService.state
        state.baseUrl = baseUrlField.text
        state.model = modelField.text
        state.language = languageField.selectedItem as String
        state.promptTemplate = promptArea.text
        settingsService.setApiKey(String(apiKeyField.password))
    }
}
