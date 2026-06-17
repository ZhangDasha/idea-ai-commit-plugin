package com.example.aicommit.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiCommitSettingsServiceTest {

    private val service = AiCommitSettingsService()

    @Test
    fun `validate should reject empty api key`() {
        val state = AiCommitSettingsState(
            baseUrl = "https://api.deepseek.com/chat/completions",
            model = "deepseek-chat",
            language = "Chinese",
            promptTemplate = "commit in {language}: {diff}"
        )
        val error = service.validate(state, "")
        assertEquals("API Key 不能为空", error)
    }

    @Test
    fun `validate should reject empty base url`() {
        val state = AiCommitSettingsState(baseUrl = "", model = "deepseek-chat")
        val error = service.validate(state, "sk-xxx")
        assertEquals("Base URL 不能为空", error)
    }

    @Test
    fun `validate should reject empty model`() {
        val state = AiCommitSettingsState(
            baseUrl = "https://api.deepseek.com",
            model = "",
            promptTemplate = "{diff}"
        )
        val error = service.validate(state, "sk-xxx")
        assertEquals("Model 不能为空", error)
    }

    @Test
    fun `validate should reject prompt without diff placeholder`() {
        val state = AiCommitSettingsState(
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-chat",
            promptTemplate = "no diff placeholder"
        )
        val error = service.validate(state, "sk-xxx")
        assertEquals("Prompt 必须包含 {diff}", error)
    }

    @Test
    fun `validate should pass for valid config`() {
        val state = AiCommitSettingsState(
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-chat",
            promptTemplate = "{diff}"
        )
        val error = service.validate(state, "sk-xxx")
        assertNull(error)
    }

    @Test
    fun `state should expose two stage defaults`() {
        val state = AiCommitSettingsState()
        assertEquals("deepseek-chat", state.analysisModel)
        assertEquals("deepseek-chat", state.composerModel)
        assertEquals(0.55, state.confidenceThreshold)
        assertEquals(12, state.maxFiles)
        assertEquals(3, state.maxHunksPerFile)
    }

    @Test
    fun `validate should reject out of range confidence threshold`() {
        val state = AiCommitSettingsState().copy(confidenceThreshold = 1.5)
        assertEquals("Confidence Threshold 必须在 0 到 1 之间", service.validate(state, "sk-xxx"))
    }

    @Test
    fun `validate should reject non positive max files`() {
        val state = AiCommitSettingsState().copy(maxFiles = 0)
        assertEquals("Max Files 必须大于 0", service.validate(state, "sk-xxx"))
    }
}
