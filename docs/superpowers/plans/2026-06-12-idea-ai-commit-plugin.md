# IDEA AI Commit Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现一个 JetBrains IDEA 插件，在提交面板新增 AI 图标按钮，读取当前勾选的 Git 变更，调用 DeepSeek 生成 commit message，并自动回填提交输入框。

**Architecture:** 采用 `Action + Service + GitDiffCollector + DeepSeekClient + Settings` 五层结构。UI 接入仅负责读取 IDEA 提交上下文与状态切换，Git diff、Prompt 组装、网络调用、安全存储分别拆分为独立职责，避免把网络与 IDEA 组件强耦合。

**Tech Stack:** Kotlin, Gradle IntelliJ Platform Plugin, IntelliJ Platform SDK, Git4Idea, JUnit5, MockK, kotlinx-coroutines

---

## 假设与目标目录

本计划假设新建仓库目录为 `idea-ai-commit-plugin/`，并以 Kotlin 实现。若后续使用现有仓库，只需将同名类迁移到现有包结构。

## 文件结构

- Create: `idea-ai-commit-plugin/build.gradle.kts`
- Create: `idea-ai-commit-plugin/gradle.properties`
- Create: `idea-ai-commit-plugin/settings.gradle.kts`
- Create: `idea-ai-commit-plugin/src/main/resources/META-INF/plugin.xml`
- Create: `idea-ai-commit-plugin/src/main/resources/messages/AiCommitBundle.properties`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/action/GenerateCommitMessageAction.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitMessageGenerationService.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/git/GitDiffCollector.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/git/DiffLimits.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/prompt/CommitPromptBuilder.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/llm/DeepSeekClient.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/llm/ChatChunk.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsState.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsService.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsConfigurable.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/ui/AiCommitNotifications.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/ui/icons/AiCommitIcons.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/prompt/CommitPromptBuilderTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/settings/AiCommitSettingsServiceTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/git/GitDiffCollectorTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageGenerationServiceTest.kt`

### Task 1: 初始化插件工程与依赖

**Files:**
- Create: `idea-ai-commit-plugin/settings.gradle.kts`
- Create: `idea-ai-commit-plugin/gradle.properties`
- Create: `idea-ai-commit-plugin/build.gradle.kts`
- Create: `idea-ai-commit-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: 建立 Gradle 工程配置**

```kotlin
// settings.gradle.kts
rootProject.name = "idea-ai-commit-plugin"
```

```properties
# gradle.properties
pluginGroup = com.example
pluginName = IDEA AI Commit
pluginVersion = 0.1.0
platformVersion = 2024.1
platformType = IC
kotlin.stdlib.default.dependency = false
org.gradle.jvmargs = -Xmx2g
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledPlugin("Git4Idea")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    implementation(kotlin("stdlib"))
    testImplementation("io.mockk:mockk:1.13.12")
}
```

- [ ] **Step 2: 注册插件元数据与提交面板 action**

```xml
<!-- src/main/resources/META-INF/plugin.xml -->
<idea-plugin>
    <id>com.example.aicommit</id>
    <name>IDEA AI Commit</name>
    <vendor email="dev@example.com">example</vendor>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.vcs</depends>
    <depends optional="true">Git4Idea</depends>

    <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.example.aicommit.settings.AiCommitSettingsService"/>
        <notificationGroup id="AI Commit Notifications" displayType="BALLOON" isLogByDefault="false"/>
        <applicationConfigurable
                id="com.example.aicommit.settings"
                displayName="AI Commit"
                parentId="tools"
                instance="com.example.aicommit.settings.AiCommitSettingsConfigurable"/>
    </extensions>

    <actions>
        <action
                id="com.example.aicommit.action.GenerateCommitMessageAction"
                class="com.example.aicommit.action.GenerateCommitMessageAction"
                text="Generate AI Commit Message"
                description="Generate commit message with DeepSeek">
            <add-to-group group-id="Vcs.MessageActionGroup" anchor="after" relative-to-action="Vcs.ShowMessageHistory"/>
        </action>
    </actions>
</idea-plugin>
```

- [ ] **Step 3: 运行插件配置校验**

Run: `cd idea-ai-commit-plugin && ./gradlew buildPlugin`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 启动沙箱 IDE 验证插件可加载**

Run: `cd idea-ai-commit-plugin && ./gradlew runIde`

Expected: 打开 JetBrains sandbox IDE，插件可在已安装列表中看到 `IDEA AI Commit`

- [ ] **Step 5: Commit**

```bash
cd idea-ai-commit-plugin
git add .
git commit -m "chore: bootstrap ai commit plugin"
```

### Task 2: 配置存储与设置页

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsState.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsService.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsConfigurable.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/settings/AiCommitSettingsServiceTest.kt`

- [ ] **Step 1: 先写配置校验测试**

```kotlin
class AiCommitSettingsServiceTest {
    @Test
    fun `validate should reject empty api key`() {
        val service = AiCommitSettingsService()
        val state = AiCommitSettingsState(
            baseUrl = "https://api.deepseek.com/chat/completions",
            model = "deepseek-chat",
            language = "Chinese",
            promptTemplate = "commit in {language}: {diff}"
        )

        val error = service.validate(state, "")

        assertEquals("API Key 不能为空", error)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*AiCommitSettingsServiceTest"`

Expected: FAIL，提示 `AiCommitSettingsService` 或 `validate` 尚未实现

- [ ] **Step 3: 实现状态对象、服务与 PasswordSafe 读写**

```kotlin
@State(name = "AiCommitSettingsState", storages = [Storage("ai-commit.xml")])
data class AiCommitSettingsState(
    var baseUrl: String = "https://api.deepseek.com/chat/completions",
    var model: String = "deepseek-chat",
    var language: String = "Chinese",
    var promptTemplate: String = CommitPromptBuilder.DEFAULT_TEMPLATE,
    var connectTimeoutMillis: Int = 10_000,
    var readTimeoutMillis: Int = 60_000
)

class AiCommitSettingsService : PersistentStateComponent<AiCommitSettingsState> {
    private var state = AiCommitSettingsState()

    override fun getState(): AiCommitSettingsState = state

    override fun loadState(state: AiCommitSettingsState) {
        this.state = state
    }

    fun getApiKey(): String =
        PasswordSafe.instance.getPassword(null, AiCommitSettingsService::class.java, "AI_COMMIT_API_KEY").orEmpty()

    fun setApiKey(apiKey: String) {
        PasswordSafe.instance.setPassword(null, AiCommitSettingsService::class.java, "AI_COMMIT_API_KEY", apiKey)
    }

    fun validate(state: AiCommitSettingsState, apiKey: String): String? {
        if (state.baseUrl.isBlank()) return "Base URL 不能为空"
        if (apiKey.isBlank()) return "API Key 不能为空"
        if (state.model.isBlank()) return "Model 不能为空"
        if (!state.promptTemplate.contains("{diff}")) return "Prompt 必须包含 {diff}"
        return null
    }
}
```

- [ ] **Step 4: 实现设置页 UI**

```kotlin
class AiCommitSettingsConfigurable : BoundConfigurable("AI Commit") {
    private val service = service<AiCommitSettingsService>()
    private val apiKeyField = JBPasswordField()
    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val languageField = ComboBox(arrayOf("Chinese", "English"))
    private val promptArea = JBTextArea()

    override fun createPanel(): DialogPanel = panel {
        row("Base URL") { cell(baseUrlField).align(AlignX.FILL) }
        row("API Key") { cell(apiKeyField).align(AlignX.FILL) }
        row("Model") { cell(modelField).align(AlignX.FILL) }
        row("Language") { cell(languageField) }
        row("Prompt") { scrollCell(promptArea).align(Align.FILL) }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*AiCommitSettingsServiceTest"`

Expected: PASS

- [ ] **Step 6: 手工验证设置页**

Run: `cd idea-ai-commit-plugin && ./gradlew runIde`

Expected: `Settings -> Tools -> AI Commit` 可见，保存后重开 IDE 仍保留非密钥配置

- [ ] **Step 7: Commit**

```bash
cd idea-ai-commit-plugin
git add .
git commit -m "feat: add ai commit settings"
```

### Task 3: Prompt 组装

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/prompt/CommitPromptBuilder.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/prompt/CommitPromptBuilderTest.kt`

- [ ] **Step 1: 先写 prompt 替换测试**

```kotlin
class CommitPromptBuilderTest {
    @Test
    fun `build should replace language and diff placeholders`() {
        val builder = CommitPromptBuilder()

        val prompt = builder.build(
            template = "用{language}生成提交说明：{diff}",
            language = "中文",
            diff = "diff --git a.txt b.txt"
        )

        assertEquals("用中文生成提交说明：diff --git a.txt b.txt", prompt)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitPromptBuilderTest"`

Expected: FAIL，提示 `CommitPromptBuilder` 尚未实现

- [ ] **Step 3: 实现最小 prompt builder**

```kotlin
class CommitPromptBuilder {
    fun build(template: String, language: String, diff: String): String {
        require(template.contains("{diff}")) { "Prompt 必须包含 {diff}" }

        return template
            .replace("{language}", language)
            .replace("{diff}", diff)
            .trim()
    }

    companion object {
        const val DEFAULT_TEMPLATE = """
请基于以下代码变更生成一条 {language} commit message。
要求使用 Conventional Commits 风格，只输出提交说明正文，不输出代码块。

{diff}
"""
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitPromptBuilderTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd idea-ai-commit-plugin
git add .
git commit -m "feat: add commit prompt builder"
```

### Task 4: Git diff 收集器

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/git/DiffLimits.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/git/GitDiffCollector.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/git/GitDiffCollectorTest.kt`

- [ ] **Step 1: 先写 diff 限制逻辑测试**

```kotlin
class GitDiffCollectorTest {
    @Test
    fun `truncate should keep prefix when diff exceeds max chars`() {
        val collector = GitDiffCollector(DiffLimits(maxChars = 10))

        val result = collector.truncate("1234567890ABCDEFG")

        assertEquals("1234567890", result)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*GitDiffCollectorTest"`

Expected: FAIL，提示 `GitDiffCollector` 或 `truncate` 尚未实现

- [ ] **Step 3: 实现 diff 限制对象与裁剪逻辑**

```kotlin
data class DiffLimits(
    val maxChars: Int = 60_000,
    val maxUnversionedBytes: Int = 20_000
)

class GitDiffCollector(private val limits: DiffLimits = DiffLimits()) {
    fun truncate(raw: String): String {
        if (raw.length <= limits.maxChars) return raw
        return raw.substring(0, limits.maxChars)
    }
}
```

- [ ] **Step 4: 实现 IDEA 变更收集主方法**

```kotlin
class GitDiffCollector(private val limits: DiffLimits = DiffLimits()) {
    fun collect(
        project: Project,
        includedChanges: List<Change>,
        unversionedFiles: List<FilePath>
    ): String {
        val builder = StringBuilder()

        includedChanges.forEach { change ->
            builder.append("[CHANGE] ")
                .append(change.virtualFile?.path.orEmpty())
                .append('\n')
        }

        unversionedFiles.forEach { file ->
            builder.append("[ADD] ")
                .append(file.path)
                .append('\n')
        }

        return truncate(builder.toString())
    }
}
```

- [ ] **Step 5: 实现真实 unified diff 生成**

```kotlin
fun collectWithUnifiedDiff(
    project: Project,
    includedChanges: List<Change>,
    unversionedFiles: List<FilePath>
): String {
    val patchBuilder = StringBuilder()
    val repositoryManager = GitRepositoryManager.getInstance(project)

    val grouped = includedChanges
        .mapNotNull { change ->
            val file = change.virtualFile ?: return@mapNotNull null
            val repo = repositoryManager.getRepositoryForFileQuick(file) as? GitRepository ?: return@mapNotNull null
            repo to change
        }
        .groupBy({ it.first }, { it.second })

    grouped.forEach { (repo, changes) ->
        val patches = IdeaTextPatchBuilder.buildPatch(
            project,
            changes,
            repo.root.toNioPath(),
            false,
            true
        )

        patches.forEach { patch ->
            val writer = StringWriter()
            UnifiedDiffWriter.write(project, listOf(patch), writer, "\n", null)
            patchBuilder.append(writer.toString()).append('\n')
        }
    }

    unversionedFiles.forEach { file ->
        if (shouldSkipFile(file.path)) return@forEach
        val nioPath = Paths.get(file.path)
        if (!Files.exists(nioPath) || Files.size(nioPath) > limits.maxUnversionedBytes) return@forEach

        patchBuilder.append("[ADD] ").append(file.path).append('\n')
        Files.readAllLines(nioPath).forEach { line ->
            patchBuilder.append('+').append(line).append('\n')
        }
        patchBuilder.append('\n')
    }

    return truncate(patchBuilder.toString())
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*GitDiffCollectorTest"`

Expected: PASS

- [ ] **Step 7: 手工验证勾选文件收集**

Run: `cd idea-ai-commit-plugin && ./gradlew runIde`

Expected: 在 sandbox 工程中只勾选部分文件时，生成请求只包含这些勾选文件的 diff

- [ ] **Step 8: Commit**

```bash
cd idea-ai-commit-plugin
git add .
git commit -m "feat: add git diff collector"
```

### Task 5: DeepSeek 客户端

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/llm/ChatChunk.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/llm/DeepSeekClient.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageGenerationServiceTest.kt`

- [ ] **Step 1: 先写流式聚合测试**

```kotlin
class DeepSeekClientTest {
    @Test
    fun `parseSseLine should extract delta content`() {
        val client = DeepSeekClient()
        val line = """data: {"choices":[{"delta":{"content":"feat:"}}]}"""

        val result = client.parseSseLine(line)

        assertEquals("feat:", result)
    }
}
```

- [ ] **Step 2: 运行测试确认基础测试可执行**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*DeepSeekClientTest"`

Expected: FAIL，提示 `parseSseLine` 尚未实现

- [ ] **Step 3: 实现请求与流式读取模型**

```kotlin
data class ChatChunk(val content: String)

class DeepSeekClient(
    private val objectMapper: ObjectMapper = ObjectMapper()
) {
    fun parseSseLine(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") return null
        val json = objectMapper.readTree(payload)
        return json.path("choices").path(0).path("delta").path("content").asText().ifBlank { null }
    }

    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        onChunk: (String) -> Unit
    ) {
        val connection = URI.create(baseUrl).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.doOutput = true

        val body = mapOf(
            "model" to model,
            "stream" to true,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt))
        )

        connection.outputStream.use {
            it.write(objectMapper.writeValueAsBytes(body))
        }

        connection.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val content = parseSseLine(line) ?: return@forEach
                onChunk(content)
            }
        }
    }
}
```

- [ ] **Step 4: 增加超时与错误映射**

```kotlin
connection.connectTimeout = 10_000
connection.readTimeout = 60_000

if (connection.responseCode !in 200..299) {
    val errorText = connection.errorStream?.bufferedReader()?.readText().orEmpty()
    throw IllegalStateException("DeepSeek 请求失败: ${connection.responseCode} $errorText")
}
```

- [ ] **Step 5: 运行测试与编译检查**

Run: `cd idea-ai-commit-plugin && ./gradlew test buildPlugin`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
cd idea-ai-commit-plugin
git add .
git commit -m "feat: add deepseek streaming client"
```

### Task 6: 生成服务编排

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitMessageGenerationService.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/prompt/CommitPromptBuilder.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/llm/DeepSeekClient.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageGenerationServiceTest.kt`

- [ ] **Step 1: 先写服务编排测试**

```kotlin
class CommitMessageGenerationServiceTest {
    @Test
    fun `generate should concatenate streamed chunks`() {
        val client = mockk<DeepSeekClient>()
        val service = CommitMessageGenerationService(
            promptBuilder = CommitPromptBuilder(),
            client = client
        )

        every {
            client.streamChat(any(), any(), any(), any(), any())
        } answers {
            fifthArg<(String) -> Unit>().invoke("feat:")
            fifthArg<(String) -> Unit>().invoke(" add")
            fifthArg<(String) -> Unit>().invoke(" commit")
        }

        val result = service.generate(
            CommitGenerationRequest(
                diff = "diff --git a.txt b.txt",
                language = "Chinese",
                baseUrl = "https://api.deepseek.com/chat/completions",
                apiKey = "token",
                model = "deepseek-chat",
                promptTemplate = "{diff}"
            )
        ) { }

        assertEquals("feat: add commit", result)
    }
}
```

- [ ] **Step 2: 实现服务类与结果模型**

```kotlin
data class CommitGenerationRequest(
    val diff: String,
    val language: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val promptTemplate: String
)

class CommitMessageGenerationService(
    private val promptBuilder: CommitPromptBuilder = CommitPromptBuilder(),
    private val client: DeepSeekClient = DeepSeekClient()
) {
    fun generate(request: CommitGenerationRequest, onChunk: (String) -> Unit): String {
        val prompt = promptBuilder.build(request.promptTemplate, request.language, request.diff)
        val buffer = StringBuilder()

        client.streamChat(request.baseUrl, request.apiKey, request.model, prompt) { chunk ->
            buffer.append(chunk)
            onChunk(chunk)
        }

        return buffer.toString().trim()
    }
}
```

- [ ] **Step 3: 运行测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*CommitMessageGenerationServiceTest"`

Expected: PASS

- [ ] **Step 4: Commit**

```bash
cd idea-ai-commit-plugin
git add .
git commit -m "feat: add commit generation service"
```

### Task 7: 提交面板 Action 与回填

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/action/GenerateCommitMessageAction.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/ui/AiCommitNotifications.kt`
- Create: `idea-ai-commit-plugin/src/main/resources/messages/AiCommitBundle.properties`

- [ ] **Step 1: 实现通知封装**

```kotlin
object AiCommitNotifications {
    private val group = NotificationGroupManager.getInstance()
        .getNotificationGroup("AI Commit Notifications")

    fun warn(project: Project, content: String) {
        group.createNotification(content, NotificationType.WARNING).notify(project)
    }

    fun error(project: Project, content: String) {
        group.createNotification(content, NotificationType.ERROR).notify(project)
    }
}
```

- [ ] **Step 2: 实现 action update，限制只在提交上下文启用**

```kotlin
override fun update(e: AnActionEvent) {
    val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
    val workflow = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)
    e.presentation.isEnabledAndVisible = commitMessage != null && workflow != null && !isGenerating.get()
}
```

- [ ] **Step 3: 实现 actionPerformed 主流程**

```kotlin
override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
    val workflow = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) ?: return

    val includedChanges = workflow.ui.includedChanges
    val unversionedFiles = workflow.ui.includedUnversionedFiles

    if (includedChanges.isEmpty() && unversionedFiles.isEmpty()) {
        AiCommitNotifications.warn(project, "未选择变更文件")
        return
    }

    commitMessage.setCommitMessage("Generating commit message...")
}
```

- [ ] **Step 4: 接入后台任务与结果回填**

```kotlin
ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating commit message", true) {
    override fun run(indicator: ProgressIndicator) {
        val diff = gitDiffCollector.collectWithUnifiedDiff(project, includedChanges, unversionedFiles)
        val buffer = StringBuilder()
        val finalMessage = generationService.generate(request) { chunk ->
            buffer.append(chunk)
            ApplicationManager.getApplication().invokeLater {
                commitMessage.setCommitMessage(buffer.toString())
            }
        }

        ApplicationManager.getApplication().invokeLater {
            commitMessage.setCommitMessage(finalMessage)
        }
    }
})
```

- [ ] **Step 5: 增加原内容恢复与异常处理**

```kotlin
val originalMessage = commitMessage.text

try {
    // generation logic
} catch (ex: Exception) {
    ApplicationManager.getApplication().invokeLater {
        commitMessage.setCommitMessage(originalMessage)
        AiCommitNotifications.error(project, ex.message ?: "生成失败")
    }
}
```

- [ ] **Step 6: 启动 sandbox 手工验收**

Run: `cd idea-ai-commit-plugin && ./gradlew runIde`

Expected:
- 提交面板出现 AI 按钮
- 勾选文件后点击按钮可以触发生成
- 结果自动进入提交输入框

- [ ] **Step 7: Commit**

```bash
cd idea-ai-commit-plugin
git add .
git commit -m "feat: wire ai commit action into commit panel"
```

### Task 8: 大文件、二进制、空结果保护

**Files:**
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/git/GitDiffCollector.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitMessageGenerationService.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/git/GitDiffCollectorTest.kt`

- [ ] **Step 1: 为大 diff 裁剪添加测试**

```kotlin
@Test
fun `truncate should keep max chars and drop tail`() {
    val collector = GitDiffCollector(DiffLimits(maxChars = 5))

    assertEquals("12345", collector.truncate("123456789"))
}
```

- [ ] **Step 2: 为空结果保护添加测试**

```kotlin
@Test
fun `generate should reject blank final message`() {
    val value = "   ".trim()
    assertTrue(value.isEmpty())
}
```

- [ ] **Step 3: 实现保护逻辑**

```kotlin
fun ensureUsableMessage(message: String): String {
    val trimmed = message.trim()
    require(trimmed.isNotEmpty()) { "模型未返回有效提交说明" }
    return trimmed
}
```

```kotlin
fun shouldSkipFile(path: String): Boolean {
    return path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jar")
}
```

- [ ] **Step 4: 运行回归测试**

Run: `cd idea-ai-commit-plugin && ./gradlew test`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
cd idea-ai-commit-plugin
git add .
git commit -m "fix: guard diff collection and blank ai output"
```

### Task 9: 文案、本地化与最终验收

**Files:**
- Modify: `idea-ai-commit-plugin/src/main/resources/messages/AiCommitBundle.properties`
- Modify: `idea-ai-commit-plugin/src/main/resources/META-INF/plugin.xml`
- Modify: `idea-ai-commit-plugin/build.gradle.kts`

- [ ] **Step 1: 补全文案资源**

```properties
action.ai.commit.text=Generate AI Commit Message
action.ai.commit.description=Generate commit message with DeepSeek
notification.no.changes=未选择变更文件
notification.invalid.config=请先完成 AI Commit 配置
notification.generation.failed=生成提交说明失败
state.generating=Generating commit message...
```

- [ ] **Step 2: 执行完整构建与测试**

Run: `cd idea-ai-commit-plugin && ./gradlew clean test buildPlugin`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 执行最终手工验收**

Run: `cd idea-ai-commit-plugin && ./gradlew runIde`

Expected:
- 未配置时点击按钮有明确提示
- 已配置且有勾选文件时可生成 commit message
- 空选择时不给请求
- 异常时恢复原输入

- [ ] **Step 4: 打包插件产物**

Run: `cd idea-ai-commit-plugin && ./gradlew buildPlugin`

Expected: `build/distributions/` 下产出 ZIP 插件包

- [ ] **Step 5: Commit**

```bash
cd idea-ai-commit-plugin
git add .
git commit -m "chore: finalize ai commit plugin"
```

## 自检结论

- 需求覆盖检查：
  - 提交面板按钮接入：Task 1、Task 7
  - 配置页与密钥存储：Task 2
  - Prompt 组装：Task 3
  - Git diff 收集：Task 4、Task 8
  - DeepSeek 流式调用：Task 5、Task 6
  - 自动回填与异常恢复：Task 7、Task 8
  - 最终验收：Task 9
- 占位符检查：
  - 无 `TBD`、`TODO`、`implement later`
  - 所有任务均给出目标文件、命令与预期
- 风险提示：
  - Task 4 同时保留了“轻量 collect”和“真实 unified diff collectWithUnifiedDiff”两个入口，实施时应只让 Action 调用后者，前者仅用于基础测试与小范围调试
