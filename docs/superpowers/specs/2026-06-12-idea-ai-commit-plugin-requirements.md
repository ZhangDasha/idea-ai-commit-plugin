# IDEA DeepSeek Commit 插件需求说明

## 1. 目标

实现一个 IntelliJ IDEA 插件，在提交面板新增一个 AI 图标按钮。用户点击后，插件自动读取当前已勾选的 Git 变更，调用 DeepSeek 分析 diff，并将生成的 commit message 自动填入 IDEA 提交说明输入框。

目标交互对齐第三方插件 `DeepSeek-Coder` 的提交面板体验，但实现代码完全自定义，不直接复用第三方反编译产物。

## 2. 背景与依据

- 第三方插件通过 `Vcs.MessageActionGroup` 将按钮挂到提交面板工具条。
- 点击动作通过 `VcsDataKeys.COMMIT_WORKFLOW_HANDLER` 获取当前提交 UI 上已勾选的变更与未跟踪文件。
- diff 生成后调用大模型接口，再通过 `VcsDataKeys.COMMIT_MESSAGE_CONTROL` 回填提交说明。
- DeepSeek 接口走 OpenAI 兼容协议即可，不依赖第三方私有 SDK。

## 3. 范围

### 3.1 本期范围

1. 在 IDEA 提交面板新增 AI commit 按钮。
2. 读取当前已勾选变更与未跟踪文件，生成可用于 LLM 分析的 diff 文本。
3. 调用 DeepSeek 接口生成 commit message。
4. 将生成结果自动写回提交说明输入框。
5. 支持生成中状态、错误提示、空选择提示。
6. 提供基础设置页：
   - API Base URL
   - API Key
   - Model
   - 输出语言
   - Prompt 模板
7. 提供最小可用测试与验收方案。

### 3.2 暂不纳入

1. 代码解释、重构建议、AI code review。
2. 聊天 Tool Window。
3. 多 VCS 类型支持，首期只保证 Git。
4. 自动提交、自动 push。
5. 云端配置同步。
6. Prompt 市场、模板中心。

## 4. 目标用户

- 使用 IntelliJ IDEA / Android Studio / JetBrains 系列 IDE 的开发者。
- 已有 Git 提交流程，希望在提交面板内直接生成规范 commit message。
- 有 DeepSeek API 使用条件，接受在本地插件中配置模型参数。

## 5. 核心用户故事

### 5.1 主流程

1. 用户在提交面板勾选若干变更文件。
2. 用户点击提交说明区域的 AI 图标按钮。
3. 插件立即校验配置与选择状态。
4. 插件收集已勾选文件的 diff。
5. 插件调用 DeepSeek 生成 commit message。
6. 插件在生成过程中显示“生成中”状态，并禁用重复点击。
7. 生成完成后，插件将文本自动填入 commit message 输入框。
8. 用户可以直接编辑、提交或重新生成。

### 5.2 异常流程

1. 未勾选文件时，按钮点击后提示“未选择变更文件”。
2. 未配置 API Key / Model / URL 时，提示配置缺失并引导到设置页。
3. 接口超时或失败时，保留原输入内容，弹出错误提示。
4. 返回空文本时，不覆盖用户原内容，并提示生成失败。

## 6. 详细交互要求

### 6.1 按钮位置与形态

- 位置：提交面板 commit message 工具条。
- 接入方式：`plugin.xml` 中将 action 加入 `Vcs.MessageActionGroup`。
- 图标：默认使用单色线性 AI 图标，适配 JetBrains 浅色 / 深色主题。
- 悬浮提示：
  - 中文：`AI 生成提交说明`
  - 英文：`Generate AI Commit Message`

### 6.2 点击后的状态变化

- 开始生成时：
  - 按钮置灰不可重复点击。
  - commit message 输入框临时显示 `Generating commit message...` 或本地化等价文案。
  - 状态栏或弹窗提示“正在分析已勾选变更”。
- 生成成功时：
  - 用模型结果覆盖临时文案。
  - 按钮恢复可点击。
- 生成失败时：
  - 恢复按钮状态。
  - 若用户原本已有 commit message，恢复原内容。
  - 显示错误提示。

### 6.3 是否展示推理过程

- 首期默认不展示 reasoning popup。
- 若后续模型支持 `reasoning_content`，只保留内部扩展点，不在首期 UI 公开。

## 7. 功能需求

### 7.1 提交面板接入

- 插件必须在启用 Git 提交工作流的 IDE 中显示按钮。
- 插件必须能从 `AnActionEvent` 中读取：
  - `VcsDataKeys.COMMIT_MESSAGE_CONTROL`
  - `VcsDataKeys.COMMIT_WORKFLOW_HANDLER`
- 仅当存在提交上下文时按钮可用；否则按钮隐藏或禁用。

### 7.2 变更收集

- 收集当前 commit UI 中“已勾选”的变更，不分析未勾选文件。
- 支持两类输入：
  - 已纳入版本控制的修改、删除、移动
  - 未跟踪但已勾选的新文件
- diff 内容需包含：
  - 变更类型
  - 文件路径
  - unified diff 正文
- 需要对以下内容做保护：
  - 二进制文件跳过
  - 大文件截断
  - 超大 diff 按 token 预算裁剪
  - 敏感文件可配置忽略

### 7.3 Prompt 组装

- 默认输出 Conventional Commits 风格。
- 默认要求：
  - 返回单条 commit message
  - 优先 `feat` / `fix` / `refactor` / `docs` / `test` / `chore` / `perf` / `style`
  - 标题简洁
  - 输出中文或配置语言
  - 不要 Markdown code block
- Prompt 模板中必须保留 `{diff}` 占位符。
- 建议支持 `{language}` 占位符。

### 7.4 DeepSeek 接口调用

- 采用 OpenAI 兼容 chat completions 接口。
- 首期支持流式输出。
- 可配置项：
  - Base URL
  - API Key
  - Model
  - Timeout
- 请求体至少包含：
  - `model`
  - `messages`
  - `stream=true`

### 7.5 结果回填

- 回填目标为 IDEA 原生 commit message 控件。
- 流式模式下允许逐段写入。
- 最终写入前需 `trim` 首尾空白。
- 如果最终内容为空，不覆盖用户内容。

### 7.6 设置页

- 设置入口：`Settings/Preferences -> Tools -> AI Commit`。
- 字段：
  - `Base URL`
  - `API Key`
  - `Model`
  - `Language`
  - `Prompt Template`
  - `Connect Timeout`
  - `Read Timeout`
- 设置校验：
  - URL 非空且格式合法
  - API Key 非空
  - Model 非空
  - Prompt 中必须含 `{diff}`
- 安全要求：
  - API Key 不落明文 XML 配置
  - 使用 `PasswordSafe` 存储密钥

## 8. 非功能需求

### 8.1 性能

- 点击按钮后 300ms 内进入“生成中”状态。
- 本地 diff 组装不能阻塞 UI 线程。
- 网络请求必须在后台线程执行。

### 8.2 稳定性

- 任一异常不得导致提交面板失效。
- 重复点击时只允许一个生成任务在执行。
- 取消任务后不得继续污染 commit message 输入框。

### 8.3 兼容性

- 首期目标 IDEA 平台版本：2024.1+ 或实施时选定的单一主线版本。
- 仅依赖 IntelliJ Platform + Git4Idea。
- 需兼容新版 Commit Tool Window 工作流，不依赖旧版提交对话框。

### 8.4 安全

- API Key 不打印日志。
- 请求日志默认关闭。
- diff 内容仅发送给用户配置的模型服务地址。

## 9. 推荐实现架构

### 9.1 模块划分

1. `action`
   - 提交面板按钮 action
2. `service`
   - 生成 commit message 的应用服务
3. `git`
   - 勾选变更读取与 diff 组装
4. `llm`
   - DeepSeek/OpenAI 兼容接口客户端
5. `settings`
   - 设置持久化、校验、安全存储
6. `prompt`
   - Prompt 模板与变量替换
7. `ui`
   - 通知、设置页、状态文案

### 9.2 关键职责边界

- Action 只做上下文读取、状态切换、触发 service。
- Git diff 组装独立，避免和 UI、网络逻辑耦合。
- LLM client 只关心请求与响应，不关心 IDEA 组件。
- Settings 负责配置读取与 API Key 安全存储。

## 10. 建议项目结构

```text
idea-ai-commit-plugin/
  src/main/kotlin/com/example/aicommit/
    action/GenerateCommitMessageAction.kt
    service/CommitMessageGenerationService.kt
    git/GitDiffCollector.kt
    llm/DeepSeekClient.kt
    llm/StreamingChatModels.kt
    prompt/CommitPromptBuilder.kt
    settings/AiCommitSettingsState.kt
    settings/AiCommitSettingsService.kt
    settings/AiCommitSettingsConfigurable.kt
    ui/Notifications.kt
    ui/icons/AiCommitIcons.kt
  src/main/resources/
    META-INF/plugin.xml
    messages/AiCommitBundle.properties
```

## 11. 验收标准

### 11.1 功能验收

1. 打开 Git 提交面板后能看到 AI 图标按钮。
2. 勾选 1 个或多个变更文件后点击按钮，可自动生成 commit message。
3. 生成结果会自动进入提交说明输入框。
4. 未勾选文件时会给出明确提示。
5. 未配置 API Key 时会提示去设置页。
6. 网络异常时不会破坏原提交流程。

### 11.2 质量验收

1. 核心逻辑具备单元测试。
2. 提交面板 action 至少具备基础集成验证或手工验证脚本。
3. 不在日志和配置文件中泄漏 API Key。
4. 二进制文件、大文件、空 diff 都有明确处理。

## 12. 风险与决策

### 12.1 主要风险

- JetBrains 不同版本提交 UI API 细节可能有差异。
- 大 diff 可能导致 token 成本和响应时间上升。
- 流式回填若处理不当，可能覆盖用户手动编辑中的内容。

### 12.2 对应策略

- 先锁定一个 IDEA 平台版本线开发与验证。
- 在 diff 收集阶段引入大小限制与截断策略。
- 生成期间禁用按钮，并只在同一次任务上下文内写入结果。

## 13. 实施前置假设

1. 实施仓库将使用 Kotlin + Gradle IntelliJ Platform Plugin。
2. 首期以 Git 为唯一目标 VCS。
3. 首期仅实现 DeepSeek，一个客户端即可。
