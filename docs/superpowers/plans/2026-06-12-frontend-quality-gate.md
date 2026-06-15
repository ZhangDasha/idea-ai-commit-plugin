# AI Commit Frontend Quality Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `IDEA AI Commit` 插件增加“获取 AI Commit 前的前端质量闸口”，并把项目级生命周期、进程回收、有界并发作为前置实现约束。

**Architecture:** 在现有 `GenerateCommitMessageAction -> CommitMessageGenerationService` 链路前新增 `qualitygate` 子系统，拆成配置模型、项目识别、命令解析、受控执行、结果汇总、确认弹窗六层。运行时资源全部挂在项目级 service 上，外部进程统一受并发闸门和取消信号控制，动作完成后释放缓冲和句柄。

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Git4Idea, JUnit5, MockK, `Task.Backgroundable`, `GeneralCommandLine`, `OSProcessHandler`

---

## 文件结构

- Modify: `idea-ai-commit-plugin/build.gradle.kts`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/action/GenerateCommitMessageAction.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/prompt/CommitPromptBuilder.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitMessageGenerationService.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsState.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsConfigurable.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsService.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/config/FrontendProjectCheckConfig.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/model/FrontendCheckType.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/model/FrontendCheckStatus.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/model/FrontendCheckResult.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/model/FrontendProjectCheckResult.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/model/FrontendCheckSessionResult.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/runtime/FrontendCheckRuntimeService.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/runtime/TrackedProcess.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/detect/FrontendProjectDetector.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/resolve/FrontendCheckCommandResolver.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/run/FrontendCheckRunner.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/run/CommandExecutionResult.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/service/FrontendQualityGateService.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/ui/QualityGateDialog.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/qualitygate/runtime/FrontendCheckRuntimeServiceTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/qualitygate/detect/FrontendProjectDetectorTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/qualitygate/resolve/FrontendCheckCommandResolverTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/qualitygate/service/FrontendQualityGateServiceTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageGenerationServiceTest.kt`

## 实现约束

- 外部进程默认全局并发上限 `2`
- 单项目同时运行的外部进程上限 `2`
- 单检查项详情输出上限 `64 * 1024`
- 单检查项摘要提取输出上限 `8 * 1024`
- 所有外部命令必须禁止 `--watch` / daemon / server 模式
- 项目关闭、任务取消、插件卸载时，必须销毁进程并清空缓冲
- 最近一次检查结果只保留在本次 action 上下文，不持久化

### Task 1: 建立质量闸口配置与数据模型

**Files:**
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsState.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsConfigurable.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsService.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/config/FrontendProjectCheckConfig.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/model/FrontendCheckType.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/model/FrontendCheckStatus.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/model/FrontendCheckResult.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/model/FrontendProjectCheckResult.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/model/FrontendCheckSessionResult.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/settings/AiCommitSettingsServiceTest.kt`

- [ ] **Step 1: 先补设置校验失败用例**

```kotlin
@Test
fun `validateFrontendCheckSettings should reject non positive timeout`() {
    val service = AiCommitSettingsService()
    val state = AiCommitSettingsState(
        connectTimeoutMillis = 10_000,
        readTimeoutMillis = 60_000,
        frontendChecksEnabled = true,
        frontendCheckTimeoutMillis = 0
    )

    val error = service.validate(state, "sk-xxx")

    assertEquals("前端检查超时时间必须大于 0", error)
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*AiCommitSettingsServiceTest.validateFrontendCheckSettings*"`

Expected: FAIL，提示 `frontendChecksEnabled` 或 `frontendCheckTimeoutMillis` 尚未定义

- [ ] **Step 3: 实现设置字段和质量闸口模型**

```kotlin
// AiCommitSettingsState.kt
@State(name = "AiCommitSettingsState", storages = [Storage("ai-commit.xml")])
data class AiCommitSettingsState(
    var baseUrl: String = "https://api.deepseek.com/chat/completions",
    var model: String = "deepseek-chat",
    var language: String = "Chinese",
    var promptTemplate: String = CommitPromptBuilder.DEFAULT_TEMPLATE,
    var connectTimeoutMillis: Int = 10_000,
    var readTimeoutMillis: Int = 60_000,
    // 前端检查总开关
    var frontendChecksEnabled: Boolean = true,
    // 所有前端检查共享一个总超时
    var frontendCheckTimeoutMillis: Int = 20_000,
    // 仅存在 warning 时附加到 prompt
    var includeWarningSummaryInPrompt: Boolean = true,
    // 按项目根目录保存手动补充命令
    var frontendProjectConfigs: MutableList<FrontendProjectCheckConfig> = mutableListOf()
)

// FrontendProjectCheckConfig.kt
data class FrontendProjectCheckConfig(
    var projectRoot: String = "",
    var typescriptCommand: String? = null,
    var eslintCommand: String? = null,
    var customCommands: MutableList<String> = mutableListOf()
)

// FrontendCheckType.kt
enum class FrontendCheckType { TYPESCRIPT, ESLINT, CUSTOM }

// FrontendCheckStatus.kt
enum class FrontendCheckStatus { PASSED, WARNING, CRITICAL }

// FrontendCheckResult.kt
data class FrontendCheckResult(
    val type: FrontendCheckType,
    val command: String?,
    val status: FrontendCheckStatus,
    val exitCode: Int?,
    val durationMillis: Long,
    val summary: String,
    val details: String
)

// FrontendProjectCheckResult.kt
data class FrontendProjectCheckResult(
    val projectRoot: String,
    val projectName: String,
    val checks: List<FrontendCheckResult>
)

// FrontendCheckSessionResult.kt
data class FrontendCheckSessionResult(
    val projects: List<FrontendProjectCheckResult>,
    val hasCritical: Boolean,
    val hasWarnings: Boolean,
    val warningSummaryForPrompt: String?,
    val requiresConfirmation: Boolean
)
```

- [ ] **Step 4: 扩展设置服务与设置页**

```kotlin
// AiCommitSettingsService.kt
fun validate(state: AiCommitSettingsState, apiKey: String): String? {
    if (state.baseUrl.isBlank()) return "Base URL 不能为空"
    if (apiKey.isBlank()) return "API Key 不能为空"
    if (state.model.isBlank()) return "Model 不能为空"
    if (!state.promptTemplate.contains("{diff}")) return "Prompt 必须包含 {diff}"
    if (state.frontendCheckTimeoutMillis <= 0) return "前端检查超时时间必须大于 0"
    return null
}

fun findFrontendProjectConfig(projectRoot: String): FrontendProjectCheckConfig? {
    return state.frontendProjectConfigs.firstOrNull { it.projectRoot == projectRoot }
}
```

```kotlin
// AiCommitSettingsConfigurable.kt
private val frontendChecksEnabledBox = JBCheckBox("启用获取 AI Commit 前前端检查")
private val frontendCheckTimeoutField = JBTextField()
private val includeWarningSummaryBox = JBCheckBox("存在警告时将摘要附加到 Prompt")

override fun createComponent(): JComponent = panel {
    row("Base URL") { cell(baseUrlField).align(AlignX.FILL) }
    row("API Key") { cell(apiKeyField).align(AlignX.FILL) }
    row("Model") { cell(modelField).align(AlignX.FILL) }
    row("Language") { cell(languageField) }
    row("Prompt Template") { scrollCell(promptArea).align(AlignX.FILL) }
    group("Frontend Checks") {
        row { cell(frontendChecksEnabledBox) }
        row("Timeout(ms)") { cell(frontendCheckTimeoutField).align(AlignX.FILL) }
        row { cell(includeWarningSummaryBox) }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*AiCommitSettingsServiceTest"`

Expected: PASS

- [ ] **Step 6: 手工验证设置页展示**

Run: `cd idea-ai-commit-plugin && ./gradlew runIde`

Expected: `Settings -> Tools -> AI Commit` 下出现 `Frontend Checks` 配置分组

- [ ] **Step 7: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/settings \
        src/main/kotlin/com/example/aicommit/qualitygate/config \
        src/main/kotlin/com/example/aicommit/qualitygate/model \
        src/test/kotlin/com/example/aicommit/settings
git commit -m "feat: add quality gate settings models"
```

### Task 2: 建立项目级生命周期、有界并发、进程回收基础设施

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/runtime/FrontendCheckRuntimeService.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/runtime/TrackedProcess.kt`
- Modify: `idea-ai-commit-plugin/src/main/resources/META-INF/plugin.xml`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/qualitygate/runtime/FrontendCheckRuntimeServiceTest.kt`

- [ ] **Step 1: 先写运行时服务测试**

```kotlin
@Test
fun `dispose should cancel and destroy all tracked processes`() {
    val processOne = mockk<TrackedProcess>(relaxed = true)
    val processTwo = mockk<TrackedProcess>(relaxed = true)
    val service = FrontendCheckRuntimeService()

    service.track(processOne)
    service.track(processTwo)
    service.dispose()

    verify { processOne.cancel("project disposed") }
    verify { processTwo.cancel("project disposed") }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*FrontendCheckRuntimeServiceTest"`

Expected: FAIL，提示 `FrontendCheckRuntimeService` 尚未实现

- [ ] **Step 3: 实现项目级运行时服务与受控进程句柄**

```kotlin
// TrackedProcess.kt
interface TrackedProcess {
    val projectRoot: String
    val commandLine: String
    fun cancel(reason: String)
}

// FrontendCheckRuntimeService.kt
@Service(Service.Level.PROJECT)
class FrontendCheckRuntimeService : Disposable {

    companion object {
        const val MAX_GLOBAL_PROCESSES: Int = 2
        const val MAX_PROCESSES_PER_PROJECT: Int = 2
    }

    private val activeProcesses = Collections.synchronizedSet(mutableSetOf<TrackedProcess>())
    private val globalSemaphore = Semaphore(MAX_GLOBAL_PROCESSES)
    private val projectCounters = ConcurrentHashMap<String, Semaphore>()

    fun track(process: TrackedProcess) {
        activeProcesses.add(process)
    }

    fun untrack(process: TrackedProcess) {
        activeProcesses.remove(process)
    }

    fun acquire(projectRoot: String) {
        globalSemaphore.acquire()
        projectCounters.computeIfAbsent(projectRoot) { Semaphore(MAX_PROCESSES_PER_PROJECT) }.acquire()
    }

    fun release(projectRoot: String) {
        projectCounters[projectRoot]?.release()
        globalSemaphore.release()
    }

    override fun dispose() {
        val snapshot = synchronized(activeProcesses) { activeProcesses.toList() }
        snapshot.forEach { it.cancel("project disposed") }
        activeProcesses.clear()
    }
}
```

- [ ] **Step 4: 注册项目级 service**

```xml
<!-- plugin.xml -->
<projectService serviceImplementation="com.example.aicommit.qualitygate.runtime.FrontendCheckRuntimeService"/>
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*FrontendCheckRuntimeServiceTest"`

Expected: PASS

- [ ] **Step 6: 手工检查生命周期约束**

Run: `cd idea-ai-commit-plugin && ./gradlew runIde`

Expected: 打开项目后插件可正常加载；关闭项目时不报 `Disposable` 泄漏相关异常

- [ ] **Step 7: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/resources/META-INF/plugin.xml \
        src/main/kotlin/com/example/aicommit/qualitygate/runtime \
        src/test/kotlin/com/example/aicommit/qualitygate/runtime
git commit -m "feat: add project scoped check runtime"
```

### Task 3: 实现前端项目识别与命令解析

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/detect/FrontendProjectDetector.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/resolve/FrontendCheckCommandResolver.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/settings/AiCommitSettingsService.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/qualitygate/detect/FrontendProjectDetectorTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/qualitygate/resolve/FrontendCheckCommandResolverTest.kt`

- [ ] **Step 1: 先写前端项目识别测试**

```kotlin
@Test
fun `detectProjects should return frontend project roots only`() {
    val detector = FrontendProjectDetector()
    val paths = listOf(
        "/repo/web/src/App.vue",
        "/repo/server/src/main/java/App.java"
    )

    val result = detector.detectProjectRoots(paths) { candidate ->
        candidate == "/repo/web" ||
            candidate == "/repo/web/package.json" ||
            candidate == "/repo/web/tsconfig.json"
    }

    assertEquals(listOf("/repo/web"), result)
}
```

- [ ] **Step 2: 先写命令解析测试**

```kotlin
@Test
fun `resolve should fallback to project config when script missing`() {
    val settings = mockk<AiCommitSettingsService>()
    every { settings.findFrontendProjectConfig("/repo/web") } returns FrontendProjectCheckConfig(
        projectRoot = "/repo/web",
        typescriptCommand = "pnpm ts:check",
        eslintCommand = "pnpm lint:eslint",
        customCommands = mutableListOf("pnpm lint:style")
    )

    val resolver = FrontendCheckCommandResolver(settings)
    val result = resolver.resolve(
        projectRoot = "/repo/web",
        packageScripts = emptyMap()
    )

    assertEquals("pnpm ts:check", result.typescriptCommand)
    assertEquals("pnpm lint:eslint", result.eslintCommand)
    assertEquals(listOf("pnpm lint:style"), result.customCommands)
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*FrontendProjectDetectorTest" --tests "*FrontendCheckCommandResolverTest"`

Expected: FAIL，提示 detector / resolver 尚未实现

- [ ] **Step 4: 实现前端项目识别器**

```kotlin
class FrontendProjectDetector {

    private val frontendExtensions = setOf(".js", ".jsx", ".ts", ".tsx", ".vue")
    private val configNames = setOf(
        "tsconfig.json",
        "eslint.config.js",
        "eslint.config.cjs",
        "eslint.config.mjs",
        ".eslintrc",
        ".eslintrc.js",
        ".eslintrc.cjs",
        ".eslintrc.json",
        ".eslintrc.yaml",
        ".eslintrc.yml"
    )

    fun detectProjectRoots(paths: List<String>, exists: (String) -> Boolean = { File(it).exists() }): List<String> {
        return paths.asSequence()
            .filter { path -> frontendExtensions.any { ext -> path.endsWith(ext, ignoreCase = true) } }
            .mapNotNull { locateProjectRoot(it, exists) }
            .distinct()
            .toList()
    }

    private fun locateProjectRoot(filePath: String, exists: (String) -> Boolean): String? {
        var current: File? = File(filePath).parentFile
        while (current != null) {
            val packageJson = File(current, "package.json").path
            if (exists(packageJson) && hasFrontendConfig(current, exists)) return current.path
            current = current.parentFile
        }
        return null
    }

    private fun hasFrontendConfig(root: File, exists: (String) -> Boolean): Boolean {
        if (exists(File(root, "tsconfig.json").path)) return true
        if (root.listFiles()?.any { it.name.startsWith("tsconfig.") && it.name.endsWith(".json") } == true) return true
        return configNames.any { exists(File(root, it).path) }
    }
}
```

- [ ] **Step 5: 实现命令解析器**

```kotlin
data class ResolvedFrontendCommands(
    val typescriptCommand: String?,
    val eslintCommand: String?,
    val customCommands: List<String>
)

class FrontendCheckCommandResolver(
    private val settingsService: AiCommitSettingsService
) {

    fun resolve(projectRoot: String, packageScripts: Map<String, String>): ResolvedFrontendCommands {
        val config = settingsService.findFrontendProjectConfig(projectRoot)
        val typescript = findScript(packageScripts, listOf("ts:check", "typecheck", "tsc"))
            ?: config?.typescriptCommand
        val eslint = findScript(packageScripts, listOf("lint:eslint", "eslint", "lint"))
            ?: config?.eslintCommand
        return ResolvedFrontendCommands(
            typescriptCommand = typescript,
            eslintCommand = eslint,
            customCommands = config?.customCommands?.filter { it.isNotBlank() }.orEmpty()
        )
    }

    private fun findScript(scripts: Map<String, String>, names: List<String>): String? {
        val matched = names.firstOrNull { scripts.containsKey(it) } ?: return null
        return "pnpm $matched"
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*FrontendProjectDetectorTest" --tests "*FrontendCheckCommandResolverTest"`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/qualitygate/detect \
        src/main/kotlin/com/example/aicommit/qualitygate/resolve \
        src/test/kotlin/com/example/aicommit/qualitygate/detect \
        src/test/kotlin/com/example/aicommit/qualitygate/resolve
git commit -m "feat: add frontend detection and command resolution"
```

### Task 4: 实现有界输出、可取消、可销毁的外部命令执行器

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/run/FrontendCheckRunner.kt`
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/run/CommandExecutionResult.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/runtime/FrontendCheckRuntimeService.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/qualitygate/service/FrontendQualityGateServiceTest.kt`

- [ ] **Step 1: 先写执行器行为测试**

```kotlin
@Test
fun `run should truncate details and release runtime permit`() {
    val runtime = mockk<FrontendCheckRuntimeService>(relaxed = true)
    val launcher = FakeCommandLauncher(
        exitCode = 1,
        stdout = "x".repeat(70 * 1024),
        stderr = "Type error"
    )
    val runner = FrontendCheckRunner(runtime, launcher)

    val result = runner.run(
        projectRoot = "/repo/web",
        type = FrontendCheckType.TYPESCRIPT,
        command = "pnpm ts:check",
        timeoutMillis = 20_000L
    )

    assertEquals(FrontendCheckStatus.CRITICAL, result.status)
    assertTrue(result.details.length <= 64 * 1024 + 32)
    verify { runtime.acquire("/repo/web") }
    verify { runtime.release("/repo/web") }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*FrontendQualityGateServiceTest.run should truncate details and release runtime permit*"`

Expected: FAIL，提示 `FrontendCheckRunner` 尚未实现

- [ ] **Step 3: 实现执行结果与执行器**

```kotlin
data class CommandExecutionResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMillis: Long
)

class FrontendCheckRunner(
    private val runtimeService: FrontendCheckRuntimeService,
    private val launcher: CommandLauncher = OsCommandLauncher()
) {

    companion object {
        private const val DETAILS_LIMIT = 64 * 1024
        private const val SUMMARY_LIMIT = 8 * 1024
    }

    fun run(projectRoot: String, type: FrontendCheckType, command: String, timeoutMillis: Long): FrontendCheckResult {
        runtimeService.acquire(projectRoot)
        try {
            val execution = launcher.execute(projectRoot, command, timeoutMillis)
            val details = truncate("${execution.stdout}\n${execution.stderr}", DETAILS_LIMIT)
            val summary = truncate(firstUsefulLine(execution), SUMMARY_LIMIT)
            val status = when {
                execution.timedOut -> FrontendCheckStatus.WARNING
                type == FrontendCheckType.CUSTOM && execution.exitCode != 0 -> FrontendCheckStatus.WARNING
                execution.exitCode == 0 -> FrontendCheckStatus.PASSED
                else -> FrontendCheckStatus.CRITICAL
            }
            return FrontendCheckResult(
                type = type,
                command = command,
                status = status,
                exitCode = execution.exitCode,
                durationMillis = execution.durationMillis,
                summary = summary,
                details = details
            )
        } finally {
            runtimeService.release(projectRoot)
        }
    }

    private fun firstUsefulLine(execution: CommandExecutionResult): String {
        return sequenceOf(execution.stderr, execution.stdout)
            .flatMap { it.lineSequence() }
            .firstOrNull { it.isNotBlank() }
            ?: "No output"
    }

    private fun truncate(value: String, limit: Int): String {
        if (value.length <= limit) return value
        return value.take(limit) + "\n[output truncated]"
    }
}
```

- [ ] **Step 4: 用 `OSProcessHandler` 实现可取消进程**

```kotlin
interface CommandLauncher {
    fun execute(projectRoot: String, command: String, timeoutMillis: Long): CommandExecutionResult
}

class OsCommandLauncher : CommandLauncher {
    override fun execute(projectRoot: String, command: String, timeoutMillis: Long): CommandExecutionResult {
        val commandLine = GeneralCommandLine("zsh", "-lc", command).withWorkDirectory(projectRoot)
        val start = System.currentTimeMillis()
        val processHandler = OSProcessHandler(commandLine)
        val output = CapturingProcessAdapter()
        processHandler.addProcessListener(output)
        processHandler.startNotify()
        val finished = processHandler.waitFor(timeoutMillis)
        if (!finished) {
            processHandler.destroyProcess()
        }
        return CommandExecutionResult(
            exitCode = if (finished) processHandler.exitCode else null,
            stdout = output.stdout.toString(),
            stderr = output.stderr.toString(),
            timedOut = !finished,
            durationMillis = System.currentTimeMillis() - start
        )
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*FrontendQualityGateServiceTest.run should truncate details and release runtime permit*"`

Expected: PASS

- [ ] **Step 6: 手工验证超时回收**

Run: `cd idea-ai-commit-plugin && ./gradlew runIde`

Expected: 人工配置一个长时间命令后，超时返回 warning，IDE 不持续保留相关 `node` 子进程

- [ ] **Step 7: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/qualitygate/run \
        src/main/kotlin/com/example/aicommit/qualitygate/runtime \
        src/test/kotlin/com/example/aicommit/qualitygate/service
git commit -m "feat: add bounded external check runner"
```

### Task 5: 实现质量闸口汇总服务与 warning prompt 附加

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/qualitygate/service/FrontendQualityGateService.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/service/CommitMessageGenerationService.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/prompt/CommitPromptBuilder.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/qualitygate/service/FrontendQualityGateServiceTest.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageGenerationServiceTest.kt`

- [ ] **Step 1: 先写 warning 摘要与汇总测试**

```kotlin
@Test
fun `evaluate should build prompt warning summary only when warning exists`() {
    val service = FrontendQualityGateService(
        detector = fakeDetector("/repo/web"),
        resolver = fakeResolver(typescript = null, eslint = "pnpm lint:eslint", custom = listOf("pnpm lint:style")),
        runner = fakeRunner(
            FrontendCheckResult(FrontendCheckType.ESLINT, "pnpm lint:eslint", FrontendCheckStatus.PASSED, 0, 10, "ok", "ok"),
            FrontendCheckResult(FrontendCheckType.CUSTOM, "pnpm lint:style", FrontendCheckStatus.WARNING, 1, 12, "style failed", "style failed")
        )
    )

    val result = service.evaluate(project = mockk(), changedPaths = listOf("/repo/web/src/App.vue"))

    assertFalse(result.hasCritical)
    assertTrue(result.hasWarnings)
    assertTrue(result.warningSummaryForPrompt!!.contains("style failed"))
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*FrontendQualityGateServiceTest"`

Expected: FAIL，提示 `FrontendQualityGateService` 尚未实现

- [ ] **Step 3: 实现质量闸口汇总服务**

```kotlin
class FrontendQualityGateService(
    private val detector: FrontendProjectDetector,
    private val resolver: FrontendCheckCommandResolver,
    private val runner: FrontendCheckRunner
) {

    fun evaluate(project: Project, changedPaths: List<String>): FrontendCheckSessionResult {
        val roots = detector.detectProjectRoots(changedPaths)
        if (roots.isEmpty()) {
            return FrontendCheckSessionResult(emptyList(), false, false, null, false)
        }

        val projectResults = roots.map { root ->
            val scripts = readPackageScripts(root)
            val resolved = resolver.resolve(root, scripts)
            val checks = mutableListOf<FrontendCheckResult>()

            if (resolved.typescriptCommand != null) {
                checks += runner.run(root, FrontendCheckType.TYPESCRIPT, resolved.typescriptCommand, 20_000)
            } else {
                checks += FrontendCheckResult(FrontendCheckType.TYPESCRIPT, null, FrontendCheckStatus.WARNING, null, 0, "未识别到 TypeScript 检查命令", "")
            }
            if (resolved.eslintCommand != null) {
                checks += runner.run(root, FrontendCheckType.ESLINT, resolved.eslintCommand, 20_000)
            } else {
                checks += FrontendCheckResult(FrontendCheckType.ESLINT, null, FrontendCheckStatus.WARNING, null, 0, "未识别到 ESLint 检查命令", "")
            }
            resolved.customCommands.forEach { command ->
                checks += runner.run(root, FrontendCheckType.CUSTOM, command, 20_000)
            }

            FrontendProjectCheckResult(root, File(root).name, checks)
        }

        val allChecks = projectResults.flatMap { it.checks }
        val hasCritical = allChecks.any { it.status == FrontendCheckStatus.CRITICAL }
        val warnings = allChecks.filter { it.status == FrontendCheckStatus.WARNING }
        return FrontendCheckSessionResult(
            projects = projectResults,
            hasCritical = hasCritical,
            hasWarnings = warnings.isNotEmpty(),
            warningSummaryForPrompt = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- ${it.summary}" },
            requiresConfirmation = hasCritical
        )
    }
}
```

- [ ] **Step 4: 修改生成请求与 prompt 组装**

```kotlin
// CommitMessageGenerationService.kt
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
```

```kotlin
// CommitPromptBuilder.kt
fun build(template: String, language: String, diff: String, warningSummary: String? = null): String {
    val base = template
        .replace("{language}", language)
        .replace("{diff}", diff)
    if (warningSummary.isNullOrBlank()) return base
    return buildString {
        append(base)
        append("\n\n附加上下文（仅供参考，不必在提交说明中直接体现）：\n")
        append(warningSummary)
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*FrontendQualityGateServiceTest" --tests "*CommitMessageGenerationServiceTest"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/qualitygate/service \
        src/main/kotlin/com/example/aicommit/service \
        src/main/kotlin/com/example/aicommit/prompt \
        src/test/kotlin/com/example/aicommit/qualitygate/service \
        src/test/kotlin/com/example/aicommit/service
git commit -m "feat: add quality gate aggregation and prompt warnings"
```

### Task 6: 接入弹窗决策与 AI Commit 主流程

**Files:**
- Create: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/ui/QualityGateDialog.kt`
- Modify: `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/action/GenerateCommitMessageAction.kt`
- Test: `idea-ai-commit-plugin/src/test/kotlin/com/example/aicommit/service/CommitMessageGenerationServiceTest.kt`

- [ ] **Step 1: 先写 action 行为测试**

```kotlin
@Test
fun `action should stop generation when dialog returns cancel`() {
    val gate = mockk<FrontendQualityGateService>()
    every { gate.evaluate(any(), any()) } returns FrontendCheckSessionResult(
        projects = listOf(),
        hasCritical = true,
        hasWarnings = false,
        warningSummaryForPrompt = null,
        requiresConfirmation = true
    )

    val dialogFactory = mockk<(Project, FrontendCheckSessionResult) -> QualityGateDialog>()
    every { dialogFactory.invoke(any(), any()).showAndGet() } returns false

    // 断言 generate 未被调用
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd idea-ai-commit-plugin && ./gradlew test --tests "*GenerateCommitMessageAction*"`

Expected: FAIL，提示 action 还未接入 quality gate

- [ ] **Step 3: 实现确认弹窗**

```kotlin
class QualityGateDialog(
    project: Project,
    private val result: FrontendCheckSessionResult
) : DialogWrapper(project) {

    init {
        title = "Frontend Check Warnings"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val summary = JTextArea(
            result.projects.flatMap { projectResult ->
                projectResult.checks.filter { it.status != FrontendCheckStatus.PASSED }.map { check ->
                    "${projectResult.projectName} / ${check.type}: ${check.summary}"
                }
            }.joinToString("\n")
        )
        summary.isEditable = false
        return JBScrollPane(summary)
    }

    override fun createActions(): Array<Action> {
        return arrayOf(cancelAction, okAction, object : DialogWrapperAction("打开设置") {
            override fun doAction(e: ActionEvent?) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "AI Commit")
            }
        })
    }
}
```

- [ ] **Step 4: 修改 action 接入质量闸口**

```kotlin
val gateService = FrontendQualityGateService(
    detector = FrontendProjectDetector(),
    resolver = FrontendCheckCommandResolver(settingsService),
    runner = FrontendCheckRunner(project.service())
)
val changedPaths = includedChanges.mapNotNull { it.virtualFile?.path } + unversionedFiles.map { it.path }
val gateResult = if (state.frontendChecksEnabled) gateService.evaluate(project, changedPaths) else null

if (gateResult?.requiresConfirmation == true) {
    val accepted = QualityGateDialog(project, gateResult).showAndGet()
    if (!accepted) return
}

val request = CommitGenerationRequest(
    diff = diff,
    language = state.language,
    baseUrl = state.baseUrl,
    apiKey = apiKey,
    model = state.model,
    promptTemplate = state.promptTemplate,
    connectTimeout = state.connectTimeoutMillis,
    readTimeout = state.readTimeoutMillis,
    warningSummary = gateResult?.warningSummaryForPrompt.takeIf { state.includeWarningSummaryInPrompt }
)
```

- [ ] **Step 5: 运行回归测试**

Run: `cd idea-ai-commit-plugin && ./gradlew test`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 261 版本手工冒烟**

Run: `cd idea-ai-commit-plugin && ./gradlew runIde`

Expected:
- 在 `IntelliJ IDEA 2026.1.x` 沙箱内可看到 AI 按钮
- 勾选前端变更时会触发检查
- 严重问题出现时弹窗可取消/继续/打开设置
- 取消生成后不会留下常驻子进程

- [ ] **Step 7: Commit**

```bash
cd idea-ai-commit-plugin
git add src/main/kotlin/com/example/aicommit/action \
        src/main/kotlin/com/example/aicommit/ui \
        src/test/kotlin/com/example/aicommit
git commit -m "feat: wire frontend quality gate into ai commit"
```

### Task 7: 兼容性与回收验证

**Files:**
- Modify: `idea-ai-commit-plugin/build.gradle.kts`
- Modify: `idea-ai-commit-plugin/gradle.properties`

- [ ] **Step 1: 增加 261 优先验证说明**

```properties
# gradle.properties
platformVersion = 2025.1
```

```kotlin
// build.gradle.kts
tasks.register("verify261Compatibility") {
    group = "verification"
    description = "Smoke-check plugin against IntelliJ IDEA 2026.1 branch assumptions"
    doLast {
        println("Verify manually on IntelliJ IDEA 2026.1.2 (IU-261.24374.151)")
    }
}
```

- [ ] **Step 2: 执行验证命令**

Run: `cd idea-ai-commit-plugin && ./gradlew test verify261Compatibility`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 记录手工检查结果**

Run: `cd idea-ai-commit-plugin && git status --short`

Expected: 仅出现本次计划涉及文件改动，无额外运行态垃圾文件

- [ ] **Step 4: Commit**

```bash
cd idea-ai-commit-plugin
git add build.gradle.kts gradle.properties
git commit -m "chore: document quality gate verification flow"
```

## 自检

- Spec coverage:
  - 检查总开关：Task 1
  - 项目级命令配置：Task 1 / Task 3
  - 前端项目识别：Task 3
  - 有界并发：Task 2 / Task 4
  - 超时、进程销毁、生命周期释放：Task 2 / Task 4 / Task 6
  - 严重问题弹窗：Task 6
  - warning prompt 附加：Task 5
  - 2026.1.2 兼容验证：Task 7
- Placeholder scan:
  - 未保留 `TODO` / `TBD`
  - 所有任务给出目标文件、命令、预期结果
- Type consistency:
  - `FrontendCheckRuntimeService` 负责并发与回收
  - `FrontendCheckRunner` 只负责执行
  - `FrontendQualityGateService` 负责汇总与严重级别判断

Plan complete and saved to `docs/superpowers/plans/2026-06-12-frontend-quality-gate.md`. Two execution options:

1. Subagent-Driven (recommended) - 我按任务逐个派发和复核
2. Inline Execution - 我在当前会话按计划分批执行

你没授权前，我不会开始编码。
