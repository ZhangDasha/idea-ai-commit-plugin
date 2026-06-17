package com.example.aicommit.service

import com.example.aicommit.model.ScoredCommitIntent
import com.example.aicommit.model.StructuredDiff

/**
 * 两阶段生成请求参数。
 * BREAKING：旧版以 diff/model/promptTemplate 为核心，现改为结构化 diff + 两阶段模型 + 置信度。
 * connectTimeout/readTimeout 暂保留兼容入口（两阶段客户端默认值生效，后续可透传）。
 */
data class CommitGenerationRequest(
    val structuredDiff: StructuredDiff,
    val language: String,
    val baseUrl: String,
    val apiKey: String,
    val analysisModel: String,
    val composerModel: String,
    val confidenceThreshold: Double,
    val connectTimeout: Int = 10_000,
    val readTimeout: Int = 60_000
)

/**
 * 两阶段编排服务：意图归纳 → 本地规则纠偏 → 文案生成 → 校验/重试/回退。
 * onChunk 仅回填第二阶段流式输出；任一阶段异常交上层（Action）恢复原输入。
 */
class CommitMessageGenerationService(
    private val analysisService: CommitIntentAnalysisService = CommitIntentAnalysisService(),
    private val scorer: CommitRuleScorer = CommitRuleScorer(),
    private val composer: CommitMessageComposerService = CommitMessageComposerService(),
    private val validator: CommitMessageValidator = CommitMessageValidator()
) {

    fun generate(request: CommitGenerationRequest, onChunk: (String) -> Unit): String {
        // 第一阶段：结构化 diff → 意图归纳
        val intent = analysisService.analyze(
            structuredDiff = request.structuredDiff,
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            model = request.analysisModel,
            language = request.language
        )
        // 本地规则层：收敛 type/scope，标记低置信度
        val scoredIntent = scorer.score(intent, request.structuredDiff, request.confidenceThreshold)
        // 第二阶段：仅基于意图生成文案（流式回填）
        val generated = composer.compose(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            model = request.composerModel,
            language = request.language,
            intent = scoredIntent,
            onChunk = onChunk
        )
        return validateWithRetry(generated, request, scoredIntent)
    }

    /** 校验：合法返回，非法重试一次，仍非法则回退到保守文案 */
    private fun validateWithRetry(
        message: String,
        request: CommitGenerationRequest,
        scoredIntent: ScoredCommitIntent
    ): String {
        return try {
            validator.validateOrFallback(message, scoredIntent)
        } catch (e: IllegalArgumentException) {
            // 重试：静默聚合（不回填，避免 UI 闪烁）
            val retried = composer.compose(
                baseUrl = request.baseUrl,
                apiKey = request.apiKey,
                model = request.composerModel,
                language = request.language,
                intent = scoredIntent
            ) { }
            try {
                validator.validateOrFallback(retried, scoredIntent)
            } catch (e2: IllegalArgumentException) {
                validator.buildFallback(scoredIntent)
            }
        }
    }
}
