package com.example.aicommit.service

import com.example.aicommit.llm.DeepSeekClient
import com.example.aicommit.model.ScoredCommitIntent
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitMessageComposerServiceTest {

    @Test
    fun `compose should return streamed commit message`() {
        val client = object : DeepSeekClient() {
            override fun streamChat(
                baseUrl: String, apiKey: String, model: String, prompt: String,
                systemPrompt: String?, connectTimeout: Int, readTimeout: Int, onChunk: (String) -> Unit
            ) {
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
            intent = ScoredCommitIntent("fix", "scope", "stabilize commit flow", "", "", 0.8)
        ) { }

        assertEquals("fix(scope): stabilize commit flow", result)
    }
}
