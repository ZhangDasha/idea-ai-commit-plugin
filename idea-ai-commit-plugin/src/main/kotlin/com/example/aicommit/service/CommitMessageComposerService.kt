package com.example.aicommit.service

import com.example.aicommit.llm.DeepSeekClient
import com.example.aicommit.model.ScoredCommitIntent
import com.example.aicommit.prompt.CommitPromptBuilder
import com.fasterxml.jackson.databind.ObjectMapper

/** Second stage: compose a message from the validated, locally scored intent. */
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
        template: String = CommitPromptBuilder.COMPOSER_TEMPLATE,
        connectTimeout: Int = 10_000,
        readTimeout: Int = 60_000,
        validationFeedback: String? = null,
        onChunk: (String) -> Unit = {}
    ): String {
        val prompt = promptBuilder.buildComposerPrompt(
            language,
            objectMapper.writeValueAsString(intent),
            template,
            validationFeedback
        )
        val buffer = StringBuilder()
        client.streamChat(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            prompt = prompt,
            systemPrompt = CommitPromptBuilder.COMPOSER_SYSTEM_PROMPT,
            connectTimeout = connectTimeout,
            readTimeout = readTimeout
        ) { chunk ->
            buffer.append(chunk)
            onChunk(chunk)
        }
        return buffer.toString().trim()
    }
}
