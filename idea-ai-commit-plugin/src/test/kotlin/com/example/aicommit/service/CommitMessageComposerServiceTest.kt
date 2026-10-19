package com.example.aicommit.service

import com.example.aicommit.llm.DeepSeekClient
import com.example.aicommit.model.ScoredCommitIntent
import kotlin.test.Test
import kotlin.test.assertTrue

class CommitMessageComposerServiceTest {

    @Test
    fun `breaking flag is serialized using the analysis schema name`() {
        val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                ScoredCommitIntent("feat", null, "移除旧接口", "", "", .9, isBreaking = true, breakingChange = "改用新接口")
            )
        )
        kotlin.test.assertEquals(true, json["isBreaking"].booleanValue())
        kotlin.test.assertEquals(false, json.has("breaking"))
    }

    @Test
    fun `compose should return streamed commit message`() {
        var capturedPrompt = ""
        var capturedTimeouts = 0 to 0
        val client = object : DeepSeekClient() {
            override fun streamChat(
                baseUrl: String, apiKey: String, model: String, prompt: String,
                systemPrompt: String?, connectTimeout: Int, readTimeout: Int, onChunk: (String) -> Unit
            ) {
                capturedPrompt = prompt
                capturedTimeouts = connectTimeout to readTimeout
                onChunk("fix(scope): ")
                onChunk("stabilize commit flow")
            }
        }

        val service = CommitMessageComposerService(client = client)
        val result = service.compose(
            baseUrl = "https://api.deepseek.com/chat/completions",
            apiKey = "token",
            model = "deepseek-chat",
            language = "Chinese",
            intent = ScoredCommitIntent("fix", "scope", "stabilize commit flow", "", "", 0.8),
            template = "{language}|{intent}",
            connectTimeout = 123,
            readTimeout = 456,
            validationFeedback = "invalid header"
        ) { }

        kotlin.test.assertEquals("fix(scope): stabilize commit flow", result)
        kotlin.test.assertEquals(123 to 456, capturedTimeouts)
        assertTrue(capturedPrompt.startsWith("Chinese|"))
        assertTrue(capturedPrompt.endsWith("## Validation feedback\ninvalid header"))
    }
}
