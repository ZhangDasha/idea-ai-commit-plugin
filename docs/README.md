# IDEA AI Commit Plugin — 技术文档

> 版本：0.1.0 | 更新日期：2026-06-12

## 1. 项目概述

JetBrains IDEA 插件，在 Git 提交面板新增 AI 图标按钮，读取当前勾选的 Git 变更 diff，调用 DeepSeek（OpenAI 兼容协议）流式生成 commit message，自动回填到提交说明输入框。

## 2. 技术栈

| 项目 | 版本 |
|------|------|
| Kotlin | 2.0.21 |
| Gradle | 8.11 |
| IntelliJ Platform Plugin | 2.5.0 |
| 目标平台 | IDEA 2024.1 ~ 2026.1 (build 241 ~ 261) |
| 测试 | JUnit5 + MockK 1.13.12 |

## 3. 项目结构

```
idea-ai-commit-plugin/
├── build.gradle.kts                  # 构建配置
├── gradle.properties                 # 版本与平台参数
├── settings.gradle.kts
└── src/
    ├── main/
    │   ├── kotlin/com/example/aicommit/
    │   │   ├── action/
    │   │   │   └── GenerateCommitMessageAction.kt   # 提交面板按钮入口
    │   │   ├── service/
    │   │   │   └── CommitMessageGenerationService.kt # 编排：Prompt → LLM → 结果
    │   │   ├── git/
    │   │   │   ├── GitDiffCollector.kt               # diff 收集与截断
    │   │   │   └── DiffLimits.kt                     # diff 大小限制参数
    │   │   ├── llm/
    │   │   │   ├── DeepSeekClient.kt                 # 流式 SSE 客户端
    │   │   │   └── ChatChunk.kt                      # 响应 chunk 模型
    │   │   ├── prompt/
    │   │   │   └── CommitPromptBuilder.kt            # Prompt 模板替换
    │   │   ├── settings/
    │   │   │   ├── AiCommitSettingsState.kt          # 配置状态（持久化 XML）
    │   │   │   ├── AiCommitSettingsService.kt        # 配置读写 + PasswordSafe
    │   │   │   └── AiCommitSettingsConfigurable.kt   # 设置页 UI
    │   │   └── ui/
    │   │       ├── AiCommitNotifications.kt          # 通知封装
    │   │       └── icons/AiCommitIcons.kt            # 图标加载
    │   └── resources/
    │       ├── META-INF/plugin.xml                   # 插件描述与扩展点
    │       ├── icons/aiCommit.svg                    # AI 按钮 SVG 图标
    │       └── messages/AiCommitBundle.properties    # 本地化文案
    └── test/
        └── kotlin/com/example/aicommit/
            ├── prompt/CommitPromptBuilderTest.kt
            ├── settings/AiCommitSettingsServiceTest.kt
            ├── git/GitDiffCollectorTest.kt
            └── service/CommitMessageGenerationServiceTest.kt
```

## 4. 架构设计

```
用户点击 AI 按钮
       │
       ▼
GenerateCommitMessageAction (action 层)
  │  校验配置 → 校验勾选 → 状态锁
  │
  ├─→ GitDiffCollector (git 层)
  │     收集 includedChanges + unversionedFiles
  │     生成 unified diff → 截断保护
  │
  ├─→ CommitMessageGenerationService (service 编排层)
  │     │
  │     ├─→ CommitPromptBuilder (prompt 层)
  │     │     替换 {language} / {diff} 占位符
  │     │
  │     └─→ DeepSeekClient (llm 层)
  │           SSE 流式请求 → 逐 chunk 回调
  │
  └─→ 回填 CommitMessage 控件
        流式写入 → 最终校验 → 异常恢复
```

### 4.1 职责边界

| 模块 | 职责 | 不负责 |
|------|------|--------|
| Action | 读取 IDEA 上下文、状态切换、触发 Service | 不做业务逻辑 |
| GitDiffCollector | diff 收集、截断、二进制跳过 | 不关心 UI 和网络 |
| CommitPromptBuilder | 模板变量替换 | 不关心 diff 来源 |
| DeepSeekClient | SSE 请求与解析 | 不关心 IDEA 组件 |
| CommitMessageGenerationService | 编排上述三者 | 不直接操作 UI |
| Settings | 配置持久化 + API Key 安全存储 | 不关心业务流程 |

## 5. 核心流程

### 5.1 按钮注册

```xml
<!-- plugin.xml -->
<action id="...GenerateCommitMessageAction"
        icon="/icons/aiCommit.svg">
    <add-to-group group-id="Vcs.MessageActionGroup"
                  anchor="after"
                  relative-to-action="Vcs.ShowMessageHistory"/>
</action>
```

通过 `Vcs.MessageActionGroup` 挂到提交面板工具条。

### 5.2 数据获取

| 数据 | DataKey | 返回类型 |
|------|---------|---------|
| Commit Message 控件 | `VcsDataKeys.COMMIT_MESSAGE_CONTROL` | `CommitMessageI` |
| 勾选变更 | `VcsDataKeys.COMMIT_WORKFLOW_UI` → `getIncludedChanges()` | `List<Change>` |
| 未跟踪文件 | `VcsDataKeys.COMMIT_WORKFLOW_UI` → `getIncludedUnversionedFiles()` | `List<FilePath>` |

### 5.3 diff 收集

- 已跟踪文件：通过 `Change.beforeRevision / afterRevision` 获取内容，生成简易 unified diff
- 未跟踪文件：直接读取文件内容，前缀 `+`
- 保护机制：
  - 二进制文件跳过（`.png`, `.jpg`, `.jar`, `.class` 等）
  - diff 总长度超过 `maxChars`（默认 60000）截断
  - 单文件超过 `maxUnversionedBytes`（默认 20000）跳过

### 5.4 LLM 调用

- 协议：OpenAI 兼容 chat completions（`stream=true`）
- SSE 逐行解析：`data: {"choices":[{"delta":{"content":"..."}}]}`
- 超时：connect 10s / read 60s（可配置）

### 5.5 回填与异常恢复

```
originalMessage = commitMessage.text    // 保存原内容
commitMessage.setCommitMessage("Generating...")

try {
    // 流式回填
    onChunk → invokeLater { setCommitMessage(buffer) }
    // 最终写入
    ensureUsableMessage(final) → setCommitMessage
} catch {
    invokeLater { setCommitMessage(originalMessage) }  // 恢复
    error notification
} finally {
    isGenerating.set(false)  // 解锁按钮
}
```

## 6. 配置说明

### 6.1 设置页字段

| 字段 | 默认值 | 存储位置 |
|------|--------|---------|
| Base URL | `https://api.deepseek.com/chat/completions` | `ai-commit.xml` |
| API Key | 空 | `PasswordSafe` |
| Model | `deepseek-chat` | `ai-commit.xml` |
| Language | `Chinese` | `ai-commit.xml` |
| Prompt Template | 见下方 | `ai-commit.xml` |
| Connect Timeout | 10000ms | `ai-commit.xml` |
| Read Timeout | 60000ms | `ai-commit.xml` |

### 6.2 默认 Prompt 模板

```
请基于以下代码变更生成一条 {language} commit message。
要求使用 Conventional Commits 风格，只输出提交说明正文，不输出代码块。

{diff}
```

必须包含 `{diff}` 占位符，可选 `{language}`。

## 7. 构建与发布

### 7.1 构建

```bash
cd idea-ai-commit-plugin
./gradlew clean buildPlugin
# 产出：build/distributions/idea-ai-commit-plugin-0.1.0.zip
```

### 7.2 沙箱测试

```bash
./gradlew runIde
```

### 7.3 安装到现有 IDEA

`Settings → Plugins → ⚙️ → Install Plugin from Disk...` → 选择 zip → 重启

## 8. 版本兼容性

| 配置项 | 值 |
|--------|-----|
| `platformVersion` | 2025.1 |
| `idea-version since-build` | 241 |
| `idea-version until-build` | 261.* |
| 兼容范围 | IDEA 2024.1 ~ 2026.1 |

如需扩展兼容范围，修改：
- `gradle.properties` → `platformVersion`
- `plugin.xml` → `<idea-version since-build="..." until-build="..."/>`

## 9. 已知问题与后续规划

### 9.1 当前版本已知问题

- diff 生成采用简易逐行对比，非标准 Myers diff 算法
- 多 VCS 仅支持 Git
- 无 reasoning_content 展示

### 9.2 后续迭代方向

| 优先级 | 功能 | 说明 |
|--------|------|------|
| P0 | 接入标准 diff 库 | 使用 `IdeaTextPatchBuilder` 替代简易 diff |
| P1 | 多模型支持 | 支持 OpenAI / Claude / 自定义 endpoint |
| P1 | Prompt 市场 | 内置多套 prompt 模板可切换 |
| P2 | reasoning 展示 | 支持 `reasoning_content` 字段弹窗 |
| P2 | 自动检测敏感文件 | `.env`, `credentials.json` 等 |
| P3 | 多 VCS | SVN / Mercurial |
| P3 | 云端配置同步 | JetBrains Settings Sync |

## 10. 开发注意事项

### 10.1 IntelliJ Platform API 要点

- `PasswordSafe` 读取需包裹 `ReadAction.compute {}` 避免在 EDT 触发 `SlowOperations`
- `CommitWorkflowUi` 通过 `VcsDataKeys.COMMIT_WORKFLOW_UI` 获取，不是从 handler 的 `.ui` 属性
- Kotlin 调用 Java getter 需用 `getIncludedChanges()` 而非属性语法（部分接口不自动生成 Kotlin 属性）
- 后台任务用 `Task.Backgroundable`，UI 更新必须 `invokeLater`
- `ActionUpdateThread.BGT` 确保按钮状态更新在后台线程

### 10.2 调试技巧

```bash
# 查看完整构建日志
./gradlew buildPlugin --stacktrace --info

# 只跑单个测试
./gradlew test --tests "*CommitPromptBuilderTest"
```
