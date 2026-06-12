package com.example.aicommit.service

import com.example.aicommit.llm.DeepSeekClient
import com.example.aicommit.prompt.CommitPromptBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitMessageGenerationServiceTest {

    @Test
    fun `generate should concatenate streamed chunks`() {
        // 模拟 DeepSeekClient，直接回调 chunks
        val client = object : DeepSeekClient() {
            override fun streamChat(
                baseUrl: String, apiKey: String, model: String, prompt: String,
                connectTimeout: Int, readTimeout: Int, onChunk: (String) -> Unit
            ) {
                onChunk("feat:")
                onChunk(" add ")
                onChunk("commit")
            }
        }

        val service = CommitMessageGenerationService(
            promptBuilder = CommitPromptBuilder(),
            client = client
        )

        val result = service.generate(
            CommitGenerationRequest(
                diff = "diff --git a.txt b.txt",
                language = "Chinese",
                baseUrl = "https://api.deepseek.com",
                apiKey = "token",
                model = "deepseek-chat",
                promptTemplate = "{diff}"
            )
        ) { }

        assertEquals("feat: add commit", result)
    }

    @Test
    fun `ensureUsableMessage should reject blank`() {
        val service = CommitMessageGenerationService()
        try {
            service.ensureUsableMessage("   ")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("模型未返回有效提交说明", e.message)
        }
    }
}
