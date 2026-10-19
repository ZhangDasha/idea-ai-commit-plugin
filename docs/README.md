# IDEA AI Commit Plugin

在 IDEA 的 Git 提交面板中，根据当前勾选的变更生成 Conventional Commits 提交信息。默认使用 DeepSeek，也支持 OpenAI Chat Completions 兼容接口。

## 使用

1. 在 **Settings → Tools → AI Commit** 配置完整接口地址、API Key、分析模型和生成模型。
2. 选择 Chinese 或 English。API Key 留空表示保留已保存的密钥。
3. 在提交面板勾选变更，点击 **Generate AI Commit Message**。
4. 插件先分析代码意图，再流式回填提交信息；最终输出经过格式校验。

生成可取消。失败或取消时恢复原提交信息；如果生成期间用户手动编辑提交框，后续结果不会覆盖这些编辑。

`0.1.1` 修复流式生成中断回填的问题：按提交框原始文档判断用户编辑，并保留模型独立返回的空格和换行。同时修复已初始化子模块、非 UTF-8 新文件/重命名和部分提交下的执行权限变更收集；权限变化会进入结构化文件摘要。

## 生成流程

```text
提交面板勾选变更
  → 按文件解析 Git 仓库和部分提交内容
  → 文件摘要、有限 hunk、原始统计和裁剪信息
  → 第一阶段：结构化意图 JSON
  → 本地保守规则：type、scope、置信度与破坏性变更
  → 第二阶段：简洁的提交信息
  → 格式校验 → 带错误反馈重试一次 → 基于真实意图回退
```

- 第一阶段分析增删代码的行为变化，只描述可见证据；不编造原因、业务背景或影响。
- 第二阶段只接收意图对象，默认生成单行标题。正文仅补充标题无法表达的已确认原因或影响。
- 文档、测试规则保留真实意图，不替换成固定文案。
- scope 使用明确的模型候选；模块分散或低置信度时省略。
- 低置信度时删除普通原因和影响，禁止普通正文；明确且一致的破坏性变更信息仍保留。
- 意图为空、格式或字段非法、破坏性变更标记自相矛盾时拒绝继续生成。
- 校验支持多行正文、`!` 和 `BREAKING CHANGE:`，同时检查 type/scope 与意图一致。
- subject 不加结束标点，优先控制在 50 个字符内；完整首行最多 72 个字符。

## Diff 收集

- 每个文件独立定位 Git 根，支持一个 IDEA 项目内的多个仓库。
- 已跟踪文件相对于 HEAD 收集；首次提交使用空 tree。
- 重命名保留旧路径和新路径；首次提交前的移动按相对于空 tree 的新增处理。子模块引用变更从父仓库收集。
- 为新文件和未暂存重命名构建文本 diff 时使用 IDEA 配置的文件编码，并识别 Unicode BOM；解码失败时明确报错。
- 部分提交快照保留执行权限变化；结构化摘要中的 `oldMode`/`newMode` 提供权限变更证据。
- 对存在部分提交 tracker 的文件，使用 IDEA 的选中 changelist 和排除状态构建精确内容。
- 部分提交内容未准备好或 changelist 不明确时直接报错，不回退到整文件 diff。
- Git 命令检查退出码，分别读取 stdout/stderr，保留空白；超时或取消时终止进程及子进程。
- 敏感路径、已识别的二进制文件以及超过 20,000 字节的未跟踪文件不读取正文；记录省略原因。
- 所有结构化 JSON 共用 100,000 字符预算，包含摘要、hunk、统计和省略信息。
- 优先保留安全文件摘要，然后分配完整 hunk；不从中间截断代码片段。
- 原始文件数量保持不变；增删行和 hunk 总数统计可读取的安全内容。模型输入标明裁剪情况。

插件提供有限的 diff 上下文，不自动读取完整相关源码、历史提交或项目 scope 约定。生成结果仍需用户检查后提交。

## 设置

| 字段 | 默认值 | 用途 |
| --- | --- | --- |
| Base URL | `https://api.deepseek.com/chat/completions` | 完整 HTTP(S) 接口地址 |
| API Key | 空 | 保存到 IDEA PasswordSafe |
| Analysis Model | `deepseek-chat` | 分析结构化 diff |
| Composer Model | `deepseek-chat` | 生成提交信息 |
| Language | `Chinese` | 意图及提交信息语言 |
| Confidence Threshold | `0.55` | 低于阈值时采用保守表达 |
| Max Files with Hunks | `12` | 保留 hunk 的文件数量上限，摘要另按全局预算保留 |
| Max Hunks / File | `3` | 每文件保留的 hunk 数量 |
| Connect Timeout | `10000 ms` | HTTP 连接超时 |
| Read Timeout | `60000 ms` | 等待流式内容的超时 |
| Analysis Prompt | 内置 Markdown 模板 | 必须包含 `{structured_diff}`，可使用 `{language}` |
| Composer Prompt | 内置 Markdown 模板 | 必须包含 `{intent}`，可使用 `{language}` |

分析 Prompt 必须要求返回 `typeCandidates`、`scopeCandidates`、`intent`、`why`、`impact`、`confidence`、`isBreaking`、`breakingChange` 字段。非破坏性变更使用 `isBreaking=false`、`breakingChange=""`；破坏性变更必须给出明确的不兼容说明，迁移方式仅在有依据时填写。

非敏感设置保存在 `aicommit-settings.xml`。旧 `model` 和 `promptTemplate` 字段仅为持久化兼容保留；旧的非默认模型在两阶段模型均为默认值时迁移。旧单阶段 Prompt 不用于两阶段请求，需要分别配置新的分析和生成 Prompt。

HTTP 请求使用 Java 异步 HTTP 客户端，支持连接等待和流式订阅取消。默认 temperature 为 0.2，max_tokens 为 2048。拒绝无限增长的输出和过长 SSE 行；服务端错误正文不会直接显示到通知中。

## 代码结构

主要代码位于 `idea-ai-commit-plugin/src/main/kotlin/com/example/aicommit/`。

| 目录 | 职责 |
| --- | --- |
| `action` | 提交面板入口、任务生命周期、保护用户编辑 |
| `git` | 仓库解析、diff 收集、部分提交、输入预算 |
| `model` | diff、意图和裁剪信息 |
| `service` | 两阶段编排、规则与输出校验 |
| `prompt` | 默认提示词、自定义模板、重试反馈 |
| `llm` | HTTP/SSE 调用及流式 JSON 聚合 |
| `settings` | 设置页、持久化和 API Key |
| `ui` | 通知及图标 |

## 构建与测试

技术栈：Kotlin 2.0.21、Gradle 8.11、IntelliJ Platform Gradle Plugin 2.5.0；编译工具链为 JDK 17，目标 SDK 为 IDEA Community 2025.1。Gradle 生成的安装包最低版本为 build `251.23774`；自动化测试使用 2025.1 SDK，其他版本需要单独验证。

```bash
cd idea-ai-commit-plugin
./gradlew test
./gradlew buildPlugin
```

测试包括真实临时 Git 仓库、首次提交、多仓库、重命名、部分内容、Git 进程取消、全局 JSON 预算、意图校验、重试反馈、配置迁移和提交框写回。网络测试使用本地 HTTP 服务验证 SSE、超时和取消，不调用真实模型接口。

安装包输出到 `idea-ai-commit-plugin/build/distributions/`。`0.1.1` 的回归测试包含带空白 token 的两阶段 HTTP 生成和提交框回填。自动化测试不能替代实际 IDEA 提交面板和真实模型输出的人工验证。
