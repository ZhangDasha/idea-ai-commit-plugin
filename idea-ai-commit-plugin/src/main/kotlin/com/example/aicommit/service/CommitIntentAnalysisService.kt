package com.example.aicommit.service

import com.example.aicommit.llm.IntentAnalyzerClient
import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.CommitIntentRules
import com.example.aicommit.model.StructuredDiff
import com.example.aicommit.prompt.CommitPromptBuilder
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode

/** First stage: structured diff to a strictly validated CommitIntent. */
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
        language: String,
        template: String = CommitPromptBuilder.ANALYSIS_TEMPLATE,
        connectTimeout: Int = 10_000,
        readTimeout: Int = 60_000,
        onChunk: (String) -> Unit = {}
    ): CommitIntent {
        require(hasDiff(structuredDiff)) { "Structured diff must not be empty" }
        val userPrompt = promptBuilder.buildAnalysisPrompt(
            language, objectMapper.writeValueAsString(structuredDiff), template
        )
        val raw = client.analyze(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            systemPrompt = promptBuilder.buildAnalysisSystemPrompt(language),
            userPrompt = userPrompt,
            connectTimeout = connectTimeout,
            readTimeout = readTimeout,
            onChunk = onChunk
        )
        return parseAndValidate(raw)
    }

    private fun parseAndValidate(raw: String): CommitIntent {
        val node: JsonNode? = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw)
        require(node != null && node.isObject) { "意图分析结果必须是 JSON 对象" }
        val required = listOf(
            "typeCandidates", "scopeCandidates", "intent", "why", "impact",
            "confidence", "isBreaking", "breakingChange"
        )
        require(required.all { node.has(it) && !node[it].isNull }) { "意图分析结果缺少必需字段" }
        require(listOf("intent", "why", "impact", "breakingChange").all { node[it].isTextual } &&
            node["confidence"].isNumber && node["isBreaking"].isBoolean &&
            listOf("typeCandidates", "scopeCandidates").all { field ->
                node[field].isArray && node[field].all { it.isTextual }
            }) { "意图分析结果字段类型非法" }
        val intent = CommitIntent(
            typeCandidates = node["typeCandidates"].map { it.textValue() },
            scopeCandidates = node["scopeCandidates"].map { it.textValue() },
            intent = node["intent"].textValue(),
            why = node["why"].textValue(),
            impact = node["impact"].textValue(),
            confidence = node["confidence"].doubleValue(),
            isBreaking = node["isBreaking"].booleanValue(),
            breakingChange = node["breakingChange"].textValue()
        )
        require(intent.typeCandidates.isNotEmpty() &&
            intent.typeCandidates.all { it in CommitIntentRules.allowedTypes }) {
            "意图分析结果包含非法 typeCandidates"
        }
        CommitIntentRules.requireSemanticIntent(intent.intent)
        require(intent.confidence.isFinite() && intent.confidence in 0.0..1.0) {
            "意图分析结果 confidence 必须在 0 到 1 之间"
        }
        require(intent.isBreaking == intent.breakingChange.isNotBlank()) {
            "破坏性变更标记必须与明确的不兼容说明一致"
        }
        return intent
    }

    private fun hasDiff(diff: StructuredDiff): Boolean = diff.files.isNotEmpty()
}
