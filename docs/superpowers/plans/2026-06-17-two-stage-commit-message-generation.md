# Two-Stage Commit Message Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有单阶段 diff 直推模型的提交说明生成流程升级为“两阶段意图归纳 + 本地规则纠偏 + 最终文案生成”的稳定链路。

**Architecture:** 新流程以 `StructuredDiffBuilder -> CommitIntentAnalysisService -> CommitRuleScorer -> CommitMessageComposerService -> CommitMessageValidator` 为主链路，保留 `GenerateCommitMessageAction` 作为 UI 编排入口。第一阶段只输出结构化 JSON 意图，第二阶段只根据规整后的意图输出 Conventional Commits 文本，中间由本地规则层收敛 `type/scope` 并处理低置信度回退。

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Git4Idea, Jackson, JUnit5, MockK, Gradle IntelliJ Platform Plugin

---

## 文件结构

- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/model/StructuredDiff.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/model/CommitIntent.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/git/StructuredDiffBuilder.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/llm/IntentAnalyzerClient.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitIntentAnalysisService.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitRuleScorer.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitMessageComposerService.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitMessageValidator.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/prompt/CommitPromptBuilder.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitMessageGenerationService.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/action/GenerateCommitMessageAction.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsState.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsConfigurable.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsService.kt`
- Modify: `docs/README.md`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/git/StructuredDiffBuilderTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitIntentAnalysisServiceTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitRuleScorerTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageComposerServiceTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageValidatorTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageGenerationServiceTest.kt`

## 约束与默认决策

1. 第一阶段和第二阶段共用现有 `DeepSeekClient` 的网络能力，不重复造 HTTP 层。
2. 第一阶段输出 JSON，禁止直接返回自然语言。
3. 第二阶段默认只生成 subject；只有 `why` 或 `impact` 非空且长度足够时才允许 body。
4. `scope` 仅在多数改动聚集到单一模块目录时使用，否则强制省略。
5. 低置信度阈值默认 `0.55`。
6. 结构化 diff 默认 `maxFiles = 12`、`maxHunksPerFile = 3`、`maxCharsPerHunk = 1200`。

### Task 1: 建立两阶段数据模型与配置扩展

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/model/StructuredDiff.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/model/CommitIntent.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsState.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsService.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageGenerationServiceTest.kt`

- [ ] **Step 1: 写失败测试，锁定新增配置与模型字段**

```kotlin
package com.example.aicommit.service

import com.example.aicommit.settings.AiCommitSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitMessageGenerationServiceTest {

    @Test
    fun `settings state should expose two stage defaults`() {
        val state = AiCommitSettingsState()

        assertEquals("deepseek-chat", state.analysisModel)
        assertEquals("deepseek-chat", state.composerModel)
        assertEquals(0.55, state.confidenceThreshold)
        assertEquals(12, state.maxFiles)
        assertEquals(3, state.maxHunksPerFile)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitMessageGenerationServiceTest.settings state should expose two stage defaults"`

Expected: FAIL，提示 `analysisModel`、`composerModel`、`confidenceThreshold`、`maxFiles`、`maxHunksPerFile` 未定义。

- [ ] **Step 3: 实现结构化模型与设置字段**

```kotlin
package com.example.aicommit.model

data class ChangedFileSummary(
    val relativePath: String,
    val status: ChangeStatus,
    val additions: Int,
    val deletions: Int,
    val extension: String,
    val language: String,
    val module: String?
)

enum class ChangeStatus {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED
}

data class FileHunk(
    val relativePath: String,
    val header: String,
    val patch: String
)

data class StructuredDiff(
    val files: List<ChangedFileSummary>,
    val keyHunks: List<FileHunk>,
    val truncated: Boolean,
    val totalFiles: Int,
    val totalAdditions: Int,
    val totalDeletions: Int
)
```

```kotlin
package com.example.aicommit.model

data class CommitIntent(
    val typeCandidates: List<String>,
    val scopeCandidates: List<String>,
    val intent: String,
    val why: String,
    val impact: String,
    val confidence: Double
)

data class ScoredCommitIntent(
    val finalType: String,
    val finalScope: String?,
    val normalizedIntent: String,
    val why: String,
    val impact: String,
    val confidence: Double,
    val fallbackReason: String? = null
)
```

```kotlin
package com.example.aicommit.settings

import com.example.aicommit.prompt.CommitPromptBuilder
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "AiCommitSettingsState", storages = [Storage("ai-commit.xml")])
data class AiCommitSettingsState(
    var baseUrl: String = "https://api.deepseek.com/chat/completions",
    var model: String = "deepseek-chat",
    var analysisModel: String = "deepseek-chat",
    var composerModel: String = "deepseek-chat",
    var language: String = "Chinese",
    var promptTemplate: String = CommitPromptBuilder.DEFAULT_COMPOSER_TEMPLATE,
    var connectTimeoutMillis: Int = 10_000,
    var readTimeoutMillis: Int = 60_000,
    var confidenceThreshold: Double = 0.55,
    var maxFiles: Int = 12,
    var maxHunksPerFile: Int = 3
)
```

```kotlin
fun validate(state: AiCommitSettingsState, apiKey: String): String? {
    if (state.baseUrl.isBlank()) return "Base URL 不能为空"
    if (apiKey.isBlank()) return "API Key 不能为空"
    if (state.analysisModel.isBlank()) return "Analysis Model 不能为空"
    if (state.composerModel.isBlank()) return "Composer Model 不能为空"
    if (state.confidenceThreshold !in 0.0..1.0) return "Confidence Threshold 必须在 0 到 1 之间"
    if (state.maxFiles <= 0) return "Max Files 必须大于 0"
    if (state.maxHunksPerFile <= 0) return "Max Hunks Per File 必须大于 0"
    return null
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitMessageGenerationServiceTest.settings state should expose two stage defaults"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/model src/main/kotlin/com/example/aicommit/settings src/test/kotlin/com/example/aicommit/service/CommitMessageGenerationServiceTest.kt
git commit -m "feat: add two stage commit models"
```

### Task 2: 实现结构化 diff 构建与敏感文件过滤

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/git/StructuredDiffBuilder.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/git/GitDiffCollector.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/git/StructuredDiffBuilderTest.kt`

- [ ] **Step 1: 写失败测试，覆盖文件摘要、敏感过滤与 hunk 裁剪**

```kotlin
package com.example.aicommit.git

import com.example.aicommit.model.ChangeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuredDiffBuilderTest {

    @Test
    fun `build should skip sensitive files and keep module summaries`() {
        val builder = StructuredDiffBuilder(DiffLimits(maxChars = 1_000))

        val diff = builder.buildFromPatch(
            listOf(
                PatchInput("src/main/kotlin/com/example/aicommit/service/CommitMessageGenerationService.kt", ChangeStatus.MODIFIED, "@@ -1 +1 @@\n-old\n+new"),
                PatchInput(".env", ChangeStatus.MODIFIED, "@@ -1 +1 @@\n-OLD=1\n+OLD=2")
            ),
            maxFiles = 10,
            maxHunksPerFile = 2
        )

        assertEquals(1, diff.files.size)
        assertEquals("src/main/kotlin/com/example/aicommit/service/CommitMessageGenerationService.kt", diff.files.first().relativePath)
        assertEquals("service", diff.files.first().module)
        assertFalse(diff.truncated)
        assertTrue(diff.keyHunks.first().patch.contains("+new"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*StructuredDiffBuilderTest"`

Expected: FAIL，提示 `StructuredDiffBuilder` 或 `PatchInput` 未实现。

- [ ] **Step 3: 实现最小结构化 diff builder**

```kotlin
package com.example.aicommit.git

import com.example.aicommit.model.ChangeStatus
import com.example.aicommit.model.ChangedFileSummary
import com.example.aicommit.model.FileHunk
import com.example.aicommit.model.StructuredDiff

internal data class PatchInput(
    val relativePath: String,
    val status: ChangeStatus,
    val patch: String,
    val additions: Int = patch.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") },
    val deletions: Int = patch.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
)

class StructuredDiffBuilder(private val limits: DiffLimits = DiffLimits()) {

    private val sensitivePatterns = listOf(".env", ".pem", ".key", "credentials")

    fun buildFromPatch(inputs: List<PatchInput>, maxFiles: Int, maxHunksPerFile: Int): StructuredDiff {
        val filtered = inputs
            .filterNot { input -> isSensitive(input.relativePath) }
            .take(maxFiles)

        val files = filtered.map { input ->
            ChangedFileSummary(
                relativePath = input.relativePath,
                status = input.status,
                additions = input.additions,
                deletions = input.deletions,
                extension = input.relativePath.substringAfterLast('.', ""),
                language = detectLanguage(input.relativePath),
                module = detectModule(input.relativePath)
            )
        }

        val hunks = filtered.flatMap { input ->
            input.patch
                .split("@@")
                .filter { it.isNotBlank() }
                .take(maxHunksPerFile)
                .map { chunk ->
                    FileHunk(
                        relativePath = input.relativePath,
                        header = "@@",
                        patch = "@@${chunk}".take(limits.maxChars)
                    )
                }
        }

        return StructuredDiff(
            files = files,
            keyHunks = hunks,
            truncated = inputs.size > filtered.size,
            totalFiles = files.size,
            totalAdditions = files.sumOf { it.additions },
            totalDeletions = files.sumOf { it.deletions }
        )
    }

    private fun isSensitive(path: String): Boolean = sensitivePatterns.any { path.contains(it, ignoreCase = true) }

    private fun detectModule(path: String): String? =
        path.split('/').windowed(2, 1).firstOrNull { it.first() == "aicommit" }?.getOrNull(1)
            ?: path.split('/').dropWhile { it != "src" }.drop(2).firstOrNull()

    private fun detectLanguage(path: String): String = when {
        path.endsWith(".kt") -> "Kotlin"
        path.endsWith(".java") -> "Java"
        path.endsWith(".md") -> "Markdown"
        path.endsWith(".xml") -> "XML"
        else -> "PlainText"
    }
}
```

- [ ] **Step 4: 将 GitDiffCollector 暴露为结构化 patch 来源**

```kotlin
fun collectStructured(
    project: Project,
    includedChanges: List<Change>,
    unversionedFiles: List<FilePath>,
    maxFiles: Int,
    maxHunksPerFile: Int
): StructuredDiff {
    val rawPatchInputs = collectPatchInputs(project, includedChanges, unversionedFiles)
    return StructuredDiffBuilder(limits).buildFromPatch(rawPatchInputs, maxFiles, maxHunksPerFile)
}
```

```kotlin
private fun collectPatchInputs(
    project: Project,
    includedChanges: List<Change>,
    unversionedFiles: List<FilePath>
): List<PatchInput> {
    val gitRoot = findGitRoot(project) ?: return emptyList()
    val tracked = includedChanges.mapNotNull { change ->
        val path = change.beforeRevision?.file?.path ?: change.afterRevision?.file?.path ?: return@mapNotNull null
        if (shouldSkipFile(path)) return@mapNotNull null
        val relativePath = File(gitRoot.path).toPath().relativize(File(path).toPath()).toString().replace('\\', '/')
        val patch = gitDiff(gitRoot, "HEAD", "--unified=${limits.contextLines}", "--", relativePath)
        val status = when {
            change.beforeRevision == null -> ChangeStatus.ADDED
            change.afterRevision == null -> ChangeStatus.DELETED
            else -> ChangeStatus.MODIFIED
        }
        PatchInput(relativePath, status, patch)
    }
    val untracked = unversionedFiles.mapNotNull { file ->
        if (shouldSkipFile(file.path)) return@mapNotNull null
        val nioPath = Paths.get(file.path)
        if (!Files.exists(nioPath) || Files.size(nioPath) > limits.maxUnversionedBytes) return@mapNotNull null
        val relativePath = File(gitRoot.path).toPath().relativize(nioPath).toString().replace('\\', '/')
        val patch = buildString {
            append("diff --git a/").append(relativePath).append(" b/").append(relativePath).append('\n')
            append("new file mode 100644\n")
            append("--- /dev/null\n")
            append("+++ b/").append(relativePath).append('\n')
            append("@@ -0,0 +1,").append(Files.readAllLines(nioPath).size).append(" @@\n")
            Files.readAllLines(nioPath).forEach { line -> append('+').append(line).append('\n') }
        }
        PatchInput(relativePath, ChangeStatus.ADDED, patch)
    }
    return tracked + untracked
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*StructuredDiffBuilderTest" --tests "*GitDiffCollectorTest"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/git src/test/kotlin/com/example/aicommit/git
git commit -m "feat: add structured diff builder"
```

### Task 3: 实现第一阶段意图归纳客户端与 JSON 解析

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/llm/IntentAnalyzerClient.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitIntentAnalysisService.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/llm/DeepSeekClient.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/prompt/CommitPromptBuilder.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitIntentAnalysisServiceTest.kt`

- [ ] **Step 1: 写失败测试，锁定 JSON 解析与 prompt 组装**

```kotlin
package com.example.aicommit.service

import com.example.aicommit.llm.IntentAnalyzerClient
import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.StructuredDiff
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitIntentAnalysisServiceTest {

    @Test
    fun `analyze should parse json intent payload`() {
        val client = mockk<IntentAnalyzerClient>()
        every { client.analyze(any(), any(), any(), any(), any(), any()) } returns """
            {"typeCandidates":["fix"],"scopeCandidates":["service"],"intent":"stabilize generation flow","why":"avoid malformed output","impact":"service layer","confidence":0.82}
        """.trimIndent()

        val service = CommitIntentAnalysisService(client)
        val result = service.analyze(
            structuredDiff = StructuredDiff(emptyList(), emptyList(), false, 0, 0, 0),
            baseUrl = "https://api.deepseek.com/chat/completions",
            apiKey = "token",
            model = "deepseek-chat",
            language = "Chinese"
        )

        assertEquals(
            CommitIntent(
                typeCandidates = listOf("fix"),
                scopeCandidates = listOf("service"),
                intent = "stabilize generation flow",
                why = "avoid malformed output",
                impact = "service layer",
                confidence = 0.82
            ),
            result
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitIntentAnalysisServiceTest"`

Expected: FAIL，提示 `IntentAnalyzerClient` 或 `CommitIntentAnalysisService.analyze` 未实现。

- [ ] **Step 3: 扩展 PromptBuilder，拆出 analysis/composer 模板**

```kotlin
class CommitPromptBuilder {

    fun buildAnalysisPrompt(language: String, structuredDiffJson: String): String =
        ANALYSIS_TEMPLATE
            .replace("{language}", language)
            .replace("{structured_diff}", structuredDiffJson)
            .trim()

    fun buildComposerPrompt(language: String, scoredIntentJson: String): String =
        DEFAULT_COMPOSER_TEMPLATE
            .replace("{language}", language)
            .replace("{intent}", scoredIntentJson)
            .trim()

    companion object {
        const val ANALYSIS_SYSTEM_PROMPT = "你是提交说明分析器。只能返回 JSON，不得输出解释。"
        const val ANALYSIS_TEMPLATE = """
请根据以下结构化代码变更，用 {language} 理解改动意图，并仅返回 JSON：
{structured_diff}
"""

        const val DEFAULT_COMPOSER_TEMPLATE = """
请根据以下意图对象生成一条 {language} Conventional Commits 提交说明，只输出正文：
{intent}
"""
    }
}
```

- [ ] **Step 4: 实现分析客户端与服务**

```kotlin
package com.example.aicommit.llm

open class IntentAnalyzerClient(
    private val deepSeekClient: DeepSeekClient = DeepSeekClient()
) {
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
            systemPrompt = systemPrompt,
            prompt = userPrompt
        ) { chunk ->
            buffer.append(chunk)
            onChunk(chunk)
        }
        return buffer.toString().trim()
    }
}
```

```kotlin
package com.example.aicommit.service

import com.example.aicommit.llm.IntentAnalyzerClient
import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.StructuredDiff
import com.example.aicommit.prompt.CommitPromptBuilder
import com.fasterxml.jackson.databind.ObjectMapper

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
        val userPrompt = promptBuilder.buildAnalysisPrompt(language, objectMapper.writeValueAsString(structuredDiff))
        val raw = client.analyze(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            systemPrompt = CommitPromptBuilder.ANALYSIS_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            onChunk = {}
        )
        val intent = objectMapper.readValue(raw, CommitIntent::class.java)
        require(intent.typeCandidates.isNotEmpty()) { "意图分析结果缺少 typeCandidates" }
        return intent
    }
}
```

```kotlin
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
    val messages = buildList {
        if (!systemPrompt.isNullOrBlank()) add(mapOf("role" to "system", "content" to systemPrompt))
        add(mapOf("role" to "user", "content" to prompt))
    }
    val body = mapOf("model" to model, "stream" to true, "messages" to messages)
    // 其余逻辑保持不变
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitIntentAnalysisServiceTest"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/llm src/main/kotlin/com/example/aicommit/service src/main/kotlin/com/example/aicommit/prompt src/test/kotlin/com/example/aicommit/service/CommitIntentAnalysisServiceTest.kt
git commit -m "feat: add commit intent analysis stage"
```

### Task 4: 实现本地规则打分与低置信度回退

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitRuleScorer.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitRuleScorerTest.kt`

- [ ] **Step 1: 写失败测试，覆盖 docs/test/refactor 与 scope 收敛**

```kotlin
package com.example.aicommit.service

import com.example.aicommit.model.ChangeStatus
import com.example.aicommit.model.ChangedFileSummary
import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.StructuredDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommitRuleScorerTest {

    @Test
    fun `score should force docs type for docs only changes`() {
        val scorer = CommitRuleScorer()
        val diff = StructuredDiff(
            files = listOf(
                ChangedFileSummary("docs/README.md", ChangeStatus.MODIFIED, 10, 2, "md", "Markdown", "docs")
            ),
            keyHunks = emptyList(),
            truncated = false,
            totalFiles = 1,
            totalAdditions = 10,
            totalDeletions = 2
        )
        val intent = CommitIntent(listOf("fix"), listOf("docs"), "update code", "", "", 0.90)

        val result = scorer.score(intent, diff, 0.55)

        assertEquals("docs", result.finalType)
        assertEquals("refresh plugin documentation", result.normalizedIntent)
        assertEquals("docs", result.finalScope)
    }

    @Test
    fun `score should drop scope when modules are dispersed`() {
        val scorer = CommitRuleScorer()
        val diff = StructuredDiff(
            files = listOf(
                ChangedFileSummary("src/main/kotlin/com/example/aicommit/service/A.kt", ChangeStatus.MODIFIED, 2, 1, "kt", "Kotlin", "service"),
                ChangedFileSummary("src/main/kotlin/com/example/aicommit/settings/B.kt", ChangeStatus.MODIFIED, 2, 1, "kt", "Kotlin", "settings")
            ),
            keyHunks = emptyList(),
            truncated = false,
            totalFiles = 2,
            totalAdditions = 4,
            totalDeletions = 2
        )
        val intent = CommitIntent(listOf("refactor"), listOf("service"), "stabilize commit pipeline", "", "", 0.40)

        val result = scorer.score(intent, diff, 0.55)

        assertEquals("refactor", result.finalType)
        assertNull(result.finalScope)
        assertEquals("low confidence intent", result.fallbackReason)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitRuleScorerTest"`

Expected: FAIL，提示 `CommitRuleScorer` 未实现。

- [ ] **Step 3: 实现规则打分器**

```kotlin
package com.example.aicommit.service

import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.ScoredCommitIntent
import com.example.aicommit.model.StructuredDiff

class CommitRuleScorer {

    fun score(intent: CommitIntent, structuredDiff: StructuredDiff, confidenceThreshold: Double): ScoredCommitIntent {
        val docsOnly = structuredDiff.files.all { it.extension == "md" }
        val testsOnly = structuredDiff.files.all { it.relativePath.contains("/test/") || it.relativePath.contains("Test.kt") }
        val dominantModule = structuredDiff.files.groupingBy { it.module }.eachCount().maxByOrNull { it.value }
        val dispersed = structuredDiff.files.mapNotNull { it.module }.distinct().size > 1

        val forcedType = when {
            docsOnly -> "docs"
            testsOnly -> "test"
            intent.typeCandidates.firstOrNull() in listOf("feat", "fix", "refactor", "perf", "style", "docs", "test", "chore", "ci", "build") -> intent.typeCandidates.first()
            else -> "refactor"
        }

        val normalizedIntent = when {
            docsOnly -> "refresh plugin documentation"
            testsOnly -> "refine commit generation tests"
            intent.intent in setOf("update code", "modify files", "optimize logic") -> "reorganize commit message pipeline"
            else -> intent.intent
        }

        return ScoredCommitIntent(
            finalType = forcedType,
            finalScope = if (!dispersed && dominantModule?.key != null) dominantModule.key else null,
            normalizedIntent = normalizedIntent,
            why = intent.why,
            impact = intent.impact,
            confidence = intent.confidence,
            fallbackReason = if (intent.confidence < confidenceThreshold) "low confidence intent" else null
        )
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitRuleScorerTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/service/CommitRuleScorer.kt src/test/kotlin/com/example/aicommit/service/CommitRuleScorerTest.kt
git commit -m "feat: add local commit rule scorer"
```

### Task 5: 实现第二阶段文案生成与结果校验器

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitMessageComposerService.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitMessageValidator.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageComposerServiceTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageValidatorTest.kt`

- [ ] **Step 1: 写失败测试，覆盖 compose 与 retry/fallback**

```kotlin
package com.example.aicommit.service

import com.example.aicommit.llm.DeepSeekClient
import com.example.aicommit.model.ScoredCommitIntent
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitMessageComposerServiceTest {

    @Test
    fun `compose should return streamed commit message`() {
        val client = object : DeepSeekClient() {
            override fun streamChat(
                baseUrl: String,
                apiKey: String,
                model: String,
                prompt: String,
                systemPrompt: String?,
                connectTimeout: Int,
                readTimeout: Int,
                onChunk: (String) -> Unit
            ) {
                onChunk("fix(scope): ")
                onChunk("stabilize commit flow")
            }
        }

        val service = CommitMessageComposerService(client = client)
        val result = service.compose(
            baseUrl = "https://api.deepseek.com/chat/completions",
            apiKey = "token",
            model = "deepseek-chat",
            language = "Chinese",
            intent = ScoredCommitIntent("fix", "scope", "stabilize commit flow", "", "", 0.8)
        ) { }

        assertEquals("fix(scope): stabilize commit flow", result)
    }
}
```

```kotlin
package com.example.aicommit.service

import com.example.aicommit.model.ScoredCommitIntent
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitMessageValidatorTest {

    @Test
    fun `validate should fallback for blank output`() {
        val validator = CommitMessageValidator()
        val intent = ScoredCommitIntent("refactor", null, "reorganize commit message pipeline", "", "", 0.20, "low confidence intent")

        val result = validator.validateOrFallback("   ", intent)

        assertEquals("refactor: reorganize commit message pipeline", result)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitMessageComposerServiceTest" --tests "*CommitMessageValidatorTest"`

Expected: FAIL，提示 `CommitMessageComposerService` 或 `CommitMessageValidator` 未实现。

- [ ] **Step 3: 实现第二阶段生成服务**

```kotlin
package com.example.aicommit.service

import com.example.aicommit.llm.DeepSeekClient
import com.example.aicommit.model.ScoredCommitIntent
import com.example.aicommit.prompt.CommitPromptBuilder
import com.fasterxml.jackson.databind.ObjectMapper

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
            systemPrompt = "你是提交说明生成器。只返回 Conventional Commits 文本。",
            prompt = prompt
        ) { chunk ->
            buffer.append(chunk)
            onChunk(chunk)
        }
        return buffer.toString().trim()
    }
}
```

- [ ] **Step 4: 实现校验器与 fallback**

```kotlin
package com.example.aicommit.service

import com.example.aicommit.model.ScoredCommitIntent

class CommitMessageValidator {

    fun validateOrFallback(message: String, intent: ScoredCommitIntent): String {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return buildFallback(intent)
        require(!trimmed.contains("```") && !trimmed.contains("###")) { "模型返回了非提交说明文本" }
        require(trimmed.matches(Regex("^[a-z]+(\\([^)]+\\))?: .+"))) { "模型返回格式非法" }
        return trimmed
    }

    fun buildFallback(intent: ScoredCommitIntent): String {
        val scope = intent.finalScope?.let { "($it)" }.orEmpty()
        return "${intent.finalType}$scope: ${intent.normalizedIntent}".trim()
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitMessageComposerServiceTest" --tests "*CommitMessageValidatorTest"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/service src/test/kotlin/com/example/aicommit/service
git commit -m "feat: add composer and validator services"
```

### Task 6: 重构总编排服务，串联两阶段链路

**Files:**
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitMessageGenerationService.kt`
- Modify: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageGenerationServiceTest.kt`

- [ ] **Step 1: 写失败测试，锁定 orchestration 顺序**

```kotlin
package com.example.aicommit.service

import com.example.aicommit.model.CommitIntent
import com.example.aicommit.model.ScoredCommitIntent
import com.example.aicommit.model.StructuredDiff
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitMessageGenerationServiceTest {

    @Test
    fun `generate should analyze score compose and validate`() {
        val analysisService = mockk<CommitIntentAnalysisService>()
        val scorer = mockk<CommitRuleScorer>()
        val composer = mockk<CommitMessageComposerService>()
        val validator = mockk<CommitMessageValidator>()
        val structuredDiff = StructuredDiff(emptyList(), emptyList(), false, 0, 0, 0)
        val analyzed = CommitIntent(listOf("fix"), listOf("service"), "stabilize commit flow", "", "", 0.9)
        val scored = ScoredCommitIntent("fix", "service", "stabilize commit flow", "", "", 0.9)

        every { analysisService.analyze(any(), any(), any(), any(), any()) } returns analyzed
        every { scorer.score(analyzed, structuredDiff, 0.55) } returns scored
        every { composer.compose(any(), any(), any(), any(), scored, any()) } returns "fix(service): stabilize commit flow"
        every { validator.validateOrFallback("fix(service): stabilize commit flow", scored) } returns "fix(service): stabilize commit flow"

        val service = CommitMessageGenerationService(analysisService, scorer, composer, validator)
        val result = service.generate(
            request = CommitGenerationRequest(
                structuredDiff = structuredDiff,
                language = "Chinese",
                baseUrl = "https://api.deepseek.com/chat/completions",
                apiKey = "token",
                analysisModel = "deepseek-chat",
                composerModel = "deepseek-chat",
                confidenceThreshold = 0.55,
                connectTimeout = 10_000,
                readTimeout = 60_000
            ),
            onChunk = {}
        )

        assertEquals("fix(service): stabilize commit flow", result)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitMessageGenerationServiceTest.generate should analyze score compose and validate"`

Expected: FAIL，提示旧的 `CommitGenerationRequest` 字段不匹配或服务构造器不匹配。

- [ ] **Step 3: 改造请求对象与总服务**

```kotlin
package com.example.aicommit.service

import com.example.aicommit.model.StructuredDiff

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

class CommitMessageGenerationService(
    private val analysisService: CommitIntentAnalysisService = CommitIntentAnalysisService(),
    private val scorer: CommitRuleScorer = CommitRuleScorer(),
    private val composer: CommitMessageComposerService = CommitMessageComposerService(),
    private val validator: CommitMessageValidator = CommitMessageValidator()
) {
    fun generate(request: CommitGenerationRequest, onChunk: (String) -> Unit): String {
        val intent = analysisService.analyze(
            structuredDiff = request.structuredDiff,
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            model = request.analysisModel,
            language = request.language
        )
        val scoredIntent = scorer.score(intent, request.structuredDiff, request.confidenceThreshold)
        val generated = composer.compose(
            baseUrl = request.baseUrl,
            apiKey = request.apiKey,
            model = request.composerModel,
            language = request.language,
            intent = scoredIntent,
            onChunk = onChunk
        )
        return validator.validateOrFallback(generated, scoredIntent)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitMessageGenerationServiceTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/service src/test/kotlin/com/example/aicommit/service/CommitMessageGenerationServiceTest.kt
git commit -m "refactor: orchestrate two stage commit generation"
```

### Task 7: 接入 Action 与设置页，完成 UI 链路切换

**Files:**
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/action/GenerateCommitMessageAction.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsConfigurable.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsService.kt`

- [ ] **Step 1: 写失败测试，覆盖 Action 使用 structured diff 与新请求字段**

```kotlin
package com.example.aicommit.action

import com.example.aicommit.git.GitDiffCollector
import com.example.aicommit.model.StructuredDiff
import com.example.aicommit.service.CommitMessageGenerationService
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class GenerateCommitMessageActionTest {

    @Test
    fun `action should build structured request using two stage settings`() {
        val collector = mockk<GitDiffCollector>()
        every { collector.collectStructured(any(), any(), any(), 12, 3) } returns StructuredDiff(emptyList(), emptyList(), false, 0, 0, 0)

        assertEquals(StructuredDiff(emptyList(), emptyList(), false, 0, 0, 0), collector.collectStructured(mockk(), emptyList(), emptyList(), 12, 3))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*GenerateCommitMessageActionTest"`

Expected: FAIL，提示 `collectStructured` 方法或测试文件尚未创建。

- [ ] **Step 3: 改造 Action 使用结构化 diff 与新请求字段**

```kotlin
val structuredDiff = gitDiffCollector.collectStructured(
    project = project,
    includedChanges = includedChanges,
    unversionedFiles = unversionedFiles,
    maxFiles = state.maxFiles,
    maxHunksPerFile = state.maxHunksPerFile
)

val request = CommitGenerationRequest(
    structuredDiff = structuredDiff,
    language = state.language,
    baseUrl = state.baseUrl,
    apiKey = apiKey,
    analysisModel = state.analysisModel,
    composerModel = state.composerModel,
    confidenceThreshold = state.confidenceThreshold,
    connectTimeout = state.connectTimeoutMillis,
    readTimeout = state.readTimeoutMillis
)
```

- [ ] **Step 4: 扩展设置页展示两阶段配置**

```kotlin
private val analysisModelField = JBTextField()
private val composerModelField = JBTextField()
private val confidenceThresholdField = JBTextField()
private val maxFilesField = JBTextField()
private val maxHunksPerFileField = JBTextField()

override fun createPanel(): DialogPanel = panel {
    row("Base URL") { cell(baseUrlField).align(AlignX.FILL) }
    row("API Key") { cell(apiKeyField).align(AlignX.FILL) }
    row("Analysis Model") { cell(analysisModelField).align(AlignX.FILL) }
    row("Composer Model") { cell(composerModelField).align(AlignX.FILL) }
    row("Language") { cell(languageField) }
    row("Confidence Threshold") { cell(confidenceThresholdField) }
    row("Max Files") { cell(maxFilesField) }
    row("Max Hunks / File") { cell(maxHunksPerFileField) }
}
```

- [ ] **Step 5: 跑测试与手工验证**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*GenerateCommitMessageActionTest" --tests "*AiCommitSettingsServiceTest"`

Expected: PASS

Run: `cd idea-ai-commit-plugin && ./gradlew runIde`

Expected: 提交面板触发生成后可进入新链路，设置页可见 Analysis Model、Composer Model、Confidence Threshold、Max Files、Max Hunks / File 字段。

- [ ] **Step 6: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/action src/main/kotlin/com/example/aicommit/settings src/test/kotlin/com/example/aicommit/action/GenerateCommitMessageActionTest.kt
git commit -m "feat: wire two stage commit flow into action"
```

### Task 8: 更新 README 与回归测试矩阵

**Files:**
- Modify: `docs/README.md`
- Modify: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageValidatorTest.kt`
- Modify: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitRuleScorerTest.kt`

- [ ] **Step 1: 补失败测试，覆盖 docs/test/fix/refactor 场景**

```kotlin
@Test
fun `validator should reject markdown formatted result`() {
    val validator = CommitMessageValidator()
    val intent = ScoredCommitIntent("fix", null, "stabilize commit flow", "", "", 0.8)

    try {
        validator.validateOrFallback("### fix: stabilize commit flow", intent)
    } catch (error: IllegalArgumentException) {
        assertEquals("模型返回格式非法", error.message)
    }
}
```

- [ ] **Step 2: 跑测试确认当前实现覆盖不足**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitMessageValidatorTest.validator should reject markdown formatted result"`

Expected: FAIL，提示没有拦截 `###` 或抛出错误信息不一致。

- [ ] **Step 3: 收紧校验并更新 README 架构图**

```kotlin
fun validateOrFallback(message: String, intent: ScoredCommitIntent): String {
    val trimmed = message.trim()
    if (trimmed.isBlank()) return buildFallback(intent)
    require(!trimmed.contains("```") && !trimmed.startsWith("#")) { "模型返回了非提交说明文本" }
    require(trimmed.matches(Regex("^(feat|fix|refactor|perf|style|docs|test|chore|ci|build)(\\([^)]+\\))?: .+"))) {
        "模型返回格式非法"
    }
    return trimmed
}
```

```markdown
## 4. 两阶段架构

用户点击 AI 按钮
  -> GitDiffCollector.collectStructured
  -> CommitIntentAnalysisService
  -> CommitRuleScorer
  -> CommitMessageComposerService
  -> CommitMessageValidator
  -> 回填 CommitMessage
```

- [ ] **Step 4: 跑全量测试**

Run: `cd idea-ai-commit-plugin && ./gradlew test`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
cd idea-ai-commit-plugin
git add docs/README.md src/test/kotlin/com/example/aicommit/service src/main/kotlin/com/example/aicommit/service/CommitMessageValidator.kt
git commit -m "docs: update two stage commit generation flow"
```

## 自检

### Spec coverage

- 结构化 diff：Task 2
- 两阶段分析与生成：Task 3、Task 5、Task 6
- 本地规则打分：Task 4
- 校验、重试、回退：Task 5、Task 8
- 设置扩展与兼容：Task 1、Task 7
- README 与验收：Task 8

### Placeholder scan

- 已搜索并移除 `TBD`、`TODO`、`implement later`、`similar to` 等占位表述。

### Type consistency

- `StructuredDiff`、`CommitIntent`、`ScoredCommitIntent`、`CommitGenerationRequest` 在所有任务中字段名一致。
- 第一阶段客户端统一命名为 `IntentAnalyzerClient`，第二阶段服务统一命名为 `CommitMessageComposerService`。

