package com.example.aicommit.service

import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.CommitIntentRules
import com.example.aicommit.model.ScoredCommitIntent
import com.example.aicommit.model.StructuredDiff

/** Applies conservative metadata rules without inventing or replacing semantic intent. */
class CommitRuleScorer {
    fun score(
        intent: CommitIntent,
        structuredDiff: StructuredDiff,
        confidenceThreshold: Double
    ): ScoredCommitIntent {
        CommitIntentRules.requireSemanticIntent(intent.intent)
        val files = structuredDiff.files
        val completeInventory = structuredDiff.totalFiles == files.size
        val docsOnly = completeInventory && files.isNotEmpty() && files.all { it.extension.equals("md", ignoreCase = true) }
        val testsOnly = completeInventory && files.isNotEmpty() && files.all {
            it.relativePath.contains("/test/") || it.relativePath.contains("Test.kt")
        }
        val dispersed = files.mapNotNull { it.module }.distinct().size > 1
        val lowConfidence = !intent.confidence.isFinite() || intent.confidence < confidenceThreshold
        val candidateType = intent.typeCandidates.firstOrNull { it in CommitIntentRules.allowedTypes }
        val finalType = when {
            candidateType == "revert" -> "revert"
            docsOnly -> "docs"
            testsOnly -> "test"
            else -> candidateType ?: "chore"
        }
        val breakingChange = intent.breakingChange.trim()
        require(intent.isBreaking || breakingChange.isEmpty()) { "破坏性变更标记与说明不一致" }
        return ScoredCommitIntent(
            finalType = finalType,
            finalScope = if (dispersed || lowConfidence) null else
                intent.scopeCandidates.firstOrNull { CommitIntentRules.validScope(it) },
            normalizedIntent = intent.intent.trim(),
            why = if (lowConfidence) "" else intent.why,
            impact = if (lowConfidence) "" else intent.impact,
            confidence = intent.confidence,
            fallbackReason = if (lowConfidence) "low confidence intent" else null,
            isBreaking = intent.isBreaking,
            breakingChange = breakingChange
        )
    }
}
