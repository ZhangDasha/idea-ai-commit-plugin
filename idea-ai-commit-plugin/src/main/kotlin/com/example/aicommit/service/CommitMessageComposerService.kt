package com.example.aicommit.service

import com.example.aicommit.llm.DeepSeekClient
import com.example.aicommit.model.ScoredCommitIntent
import com.example.aicommit.prompt.CommitPromptBuilder
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 第二阶段文案生成：仅基于规则层修正后的意图生成 Conventional Commits 文本。
 * 不携带完整原始 diff，通过 onChunk 流式回填 UI。
 */
class CommitMessageComposerService(
    private val client: DeepSeekClient = DeepSeekClient(),
    private val promptBuilder: CommitPromptBuilder = CommitPromptBuilder(),
    private val objectMapper: ObjectMapper = ObjectMapper()
) {

    fun compose(
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String,
        intent: ScoredCommitIntent,
        onChunk: (String) -> Unit
    ): String {
        val prompt = promptBuilder.buildComposerPrompt(language, objectMapper.writeValueAsString(intent))
        val buffer = StringBuilder()
        client.streamChat(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            prompt = prompt,
            systemPrompt = CommitPromptBuilder.COMPOSER_SYSTEM_PROMPT
        ) { chunk ->
            buffer.append(chunk)
            onChunk(chunk)
        }
        return buffer.toString().trim()
    }
}
