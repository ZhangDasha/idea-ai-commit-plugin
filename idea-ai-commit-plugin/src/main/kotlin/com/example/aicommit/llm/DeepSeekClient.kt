package com.example.aicommit.llm

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.HttpURLConnection
import java.net.URI

/**
 * DeepSeek 流式聊天客户端，兼容 OpenAI chat completions 协议
 */
open class DeepSeekClient(
    private val objectMapper: ObjectMapper = ObjectMapper()
) {

    /** 解析 SSE 单行，提取 delta content */
    fun parseSseLine(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") return null
        val json = objectMapper.readTree(payload)
        return json.path("choices").path(0).path("delta").path("content").asText().ifBlank { null }
    }

    /**
     * 发起流式 chat 请求
     * @param onChunk 每收到一个 chunk 回调一次
     */
    open fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String? = null,
        connectTimeout: Int = 10_000,
        readTimeout: Int = 60_000,
        onChunk: (String) -> Unit
    ) {
        val connection = URI.create(baseUrl).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.connectTimeout = connectTimeout
        connection.readTimeout = readTimeout
        connection.doOutput = true

        val body = mapOf(
            "model" to model,
            "stream" to true,
            "messages" to buildList {
                // system 角色存在时置于首位，与 user 角色分离
                if (!systemPrompt.isNullOrBlank()) add(mapOf("role" to "system", "content" to systemPrompt))
                add(mapOf("role" to "user", "content" to prompt))
            }
        )

        connection.outputStream.use { it.write(objectMapper.writeValueAsBytes(body)) }

        // 非 2xx 直接抛异常
        if (connection.responseCode !in 200..299) {
            val errorText = connection.errorStream?.bufferedReader()?.readText().orEmpty()
            throw IllegalStateException("DeepSeek 请求失败: ${connection.responseCode} $errorText")
        }

        // 逐行读取 SSE
        connection.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val content = parseSseLine(line) ?: return@forEach
                onChunk(content)
            }
        }
    }
}
