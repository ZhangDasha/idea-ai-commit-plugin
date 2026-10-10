package com.example.aicommit.settings

import com.example.aicommit.prompt.CommitPromptBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiCommitSettingsServiceTest {
    private val service = AiCommitSettingsService()

    @Test fun `validate requires credentials and valid endpoint`() {
        assertEquals("API Key 不能为空", service.validate(AiCommitSettingsState(), ""))
        assertEquals("Base URL 不能为空", service.validate(AiCommitSettingsState(baseUrl = ""), "key"))
        for (url in listOf("file:///tmp/a", "https://", "https://user:pass@example.com", "https://example.com/#fragment")) {
            assertEquals("Base URL 必须为有效的 HTTP(S) 地址", service.validate(AiCommitSettingsState(baseUrl = url), "key"))
        }
    }

    @Test fun `validate uses active two stage fields not legacy placeholders`() {
        val state = AiCommitSettingsState(model = "", promptTemplate = "old custom prompt without diff")
        assertNull(service.validate(state, "key"))
        assertEquals("Analysis Model 不能为空", service.validate(state.copy(analysisModel = ""), "key"))
        assertEquals("Composer Model 不能为空", service.validate(state.copy(composerModel = ""), "key"))
        assertEquals("分析 Prompt 必须包含 {structured_diff}", service.validate(state.copy(analysisPromptTemplate = "no input"), "key"))
        assertEquals("生成 Prompt 必须包含 {intent}", service.validate(state.copy(composerPromptTemplate = "no input"), "key"))
    }

    @Test fun `validate rejects invalid numeric configuration`() {
        for (confidence in listOf(-0.1, 1.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals("Confidence Threshold 必须在 0 到 1 之间", service.validate(AiCommitSettingsState(confidenceThreshold = confidence), "key"))
        }
        assertEquals("仅支持 Chinese 或 English 输出语言", service.validate(AiCommitSettingsState(language = "unknown"), "key"))
        assertEquals("Max Files 必须大于 0", service.validate(AiCommitSettingsState(maxFiles = 0), "key"))
        assertEquals("Max Hunks Per File 必须大于 0", service.validate(AiCommitSettingsState(maxHunksPerFile = 0), "key"))
        assertEquals("请求超时必须大于 0", service.validate(AiCommitSettingsState(readTimeoutMillis = 0), "key"))
        assertEquals("请求超时必须大于 0", service.validate(AiCommitSettingsState(connectTimeoutMillis = -1), "key"))
    }

    @Test fun `old model migrates only when both stages use defaults`() {
        service.loadState(AiCommitSettingsState(model = "custom-model"))
        assertEquals("custom-model", service.state.analysisModel)
        assertEquals("custom-model", service.state.composerModel)
        service.loadState(AiCommitSettingsState(model = "old", analysisModel = "analysis"))
        assertEquals("analysis", service.state.analysisModel)
        assertEquals("deepseek-flash", service.state.composerModel)
    }

    @Test fun `explicit default models are not replaced by the old model on reload`() {
        service.loadState(AiCommitSettingsState(model = "old-custom"))
        service.updateState(service.state.copy(analysisModel = "deepseek-chat", composerModel = "deepseek-chat"))
        service.loadState(service.state.copy())
        assertEquals("deepseek-chat", service.state.analysisModel)
        assertEquals("deepseek-chat", service.state.composerModel)
    }

    @Test fun `defaults expose effective prompts and limits`() {
        val state = AiCommitSettingsState()
        assertEquals("deepseek-flash", state.analysisModel)
        assertEquals("deepseek-flash", state.composerModel)
        assertEquals(CommitPromptBuilder.ANALYSIS_TEMPLATE, state.analysisPromptTemplate)
        assertEquals(CommitPromptBuilder.COMPOSER_TEMPLATE, state.composerPromptTemplate)
        assertEquals(12, state.maxFiles)
        assertEquals(3, state.maxHunksPerFile)
        assertNull(service.validate(state, "key"))
    }
}
