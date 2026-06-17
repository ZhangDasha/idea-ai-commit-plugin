package com.example.aicommit.service

import com.example.aicommit.llm.IntentAnalyzerClient
import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.StructuredDiff
import com.example.aicommit.prompt.CommitPromptBuilder
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 第一阶段编排：结构化 diff → 组装 analysis prompt → 调客户端 → 解析为 CommitIntent。
 * 不负责规则打分与最终文案生成。
 */
class CommitIntentAnalysisService(
    private val client: IntentAnalyzerClient = IntentAnalyzerClient(),
    private val promptBuilder: CommitPromptBuilder = CommitPromptBuilder(),
    private val objectMapper: ObjectMapper = ObjectMapper()
) {

    fun analyze(
        structuredDiff: StructuredDiff,
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String
    ): CommitIntent {
        // 结构化 diff 序列化为 JSON 喂给第一阶段
        val userPrompt = promptBuilder.buildAnalysisPrompt(language, objectMapper.writeValueAsString(structuredDiff))
        val raw = client.analyze(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            systemPrompt = CommitPromptBuilder.ANALYSIS_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            onChunk = {}
        )
        // CommitIntent 字段有默认值，Jackson 可无参反序列化
        val intent = objectMapper.readValue(raw, CommitIntent::class.java)
        // 关键字段缺失视为第一阶段失败，不得进入第二阶段
        require(intent.typeCandidates.isNotEmpty()) { "意图分析结果缺少 typeCandidates" }
        return intent
    }
}
