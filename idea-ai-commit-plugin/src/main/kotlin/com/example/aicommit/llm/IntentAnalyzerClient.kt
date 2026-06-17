package com.example.aicommit.llm

/**
 * 第一阶段意图归纳客户端：封装 DeepSeek 流式调用，聚合返回完整 JSON 文本。
 * 复用 DeepSeekClient 的网络能力，不重复实现 HTTP/SSE 层。
 */
open class IntentAnalyzerClient(
    private val deepSeekClient: DeepSeekClient = DeepSeekClient()
) {
    /**
     * 发起第一阶段请求，聚合流式 chunk 为完整字符串返回。
     * @param onChunk 流式回调（第一阶段一般静默聚合，可传 {}）
     */
    open fun analyze(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        onChunk: (String) -> Unit
    ): String {
        val buffer = StringBuilder()
        deepSeekClient.streamChat(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            prompt = userPrompt,
            systemPrompt = systemPrompt,
            onChunk = { chunk ->
                buffer.append(chunk)
                onChunk(chunk)
            }
        )
        return buffer.toString().trim()
    }
}
