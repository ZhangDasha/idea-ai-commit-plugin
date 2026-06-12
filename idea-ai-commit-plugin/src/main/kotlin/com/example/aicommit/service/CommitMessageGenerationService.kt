package com.example.aicommit.service

import com.example.aicommit.llm.DeepSeekClient
import com.example.aicommit.prompt.CommitPromptBuilder

/**
 * 生成请求参数
 */
data class CommitGenerationRequest(
    val diff: String,
    val language: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val promptTemplate: String,
    val connectTimeout: Int = 10_000,
    val readTimeout: Int = 60_000,
    val warningSummary: String? = null
)

/**
 * 编排服务：Prompt 组装 → 调用 LLM → 聚合流式结果
 */
class CommitMessageGenerationService(
    private val promptBuilder: CommitPromptBuilder = CommitPromptBuilder(),
    private val client: DeepSeekClient = DeepSeekClient()
) {

    /**
     * 执行生成，返回完整 commit message
     * @param onChunk 流式回调（用于 UI 逐步回填）
     */
    fun generate(request: CommitGenerationRequest, onChunk: (String) -> Unit): String {
        val prompt = promptBuilder.build(request.promptTemplate, request.language, request.diff, request.warningSummary)
        val buffer = StringBuilder()

        client.streamChat(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            model = request.model,
            prompt = prompt,
            connectTimeout = request.connectTimeout,
            readTimeout = request.readTimeout
        ) { chunk ->
            buffer.append(chunk)
            onChunk(chunk)
        }

        return buffer.toString().trim()
    }

    /** 校验最终结果是否可用，不可用则抛异常 */
    fun ensureUsableMessage(message: String): String {
        val trimmed = message.trim()
        require(trimmed.isNotEmpty()) { "模型未返回有效提交说明" }
        return trimmed
    }
}
