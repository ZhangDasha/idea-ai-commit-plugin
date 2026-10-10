# IDEA AI Commit Plugin

在 IDEA 的 Git 提交面板中，根据当前勾选的变更生成 Conventional Commits 提交信息。默认使用 DeepSeek，也支持 OpenAI Chat Completions 兼容接口。

## 使用

1. 在 **Settings → Tools → AI Commit** 配置接口 Base URL、API Key、分析模型和生成模型。Base URL 可以是服务根地址、`/v1` 或完整聊天地址；模型填写 API ID（例如 `deepseek-flash`），不能使用展示名称。
2. 选择 Chinese 或 English。API Key 留空表示保留已保存的密钥。
3. 在提交面板勾选变更，点击 **Generate AI Commit Message**。
4. 插件先分析代码意图，再流式回填提交信息；最终输出经过格式校验。

生成可取消。失败或取消时恢复原提交信息；失败通知明确说明没有新结果，避免将保留的历史提交误认为生成结果。如果生成期间用户手动编辑提交框，后续结果不会覆盖这些编辑。写回使用原始文档文本比较，避免 IDEA 去掉末尾空白后把流式草稿误判为手动编辑。

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
- 所选语言独立写入两个阶段的 system 消息，自定义模板缺少 `{language}` 时也生效。最终标题、普通正文和破坏性变更说明检查自然语言，允许 type、scope、技术标识符和引号内源码字面量保留原文。
- 中文请求中的纯英文输出会触发一次带语言错误反馈的重试；英文意图不能作为中文请求的本地回退。不能安全回退时提示错误并恢复原文。
- 回退保留完整意图，不截断单词、技术标识符或句子；无法满足标题上限时提示错误。
- 每次生成重新请求模型，没有提交信息结果缓存。相同输入可能得到相同措辞。

## Diff 收集

- 每个文件独立定位 Git 根，支持一个 IDEA 项目内的多个仓库。
- 已跟踪文件相对于 HEAD 收集；首次提交使用空 tree。
- 重命名保留旧路径和新路径；首次提交前的移动按相对于空 tree 的新增处理。
- 对存在部分提交 tracker 的文件，使用 IDEA 的选中 changelist 和排除状态构建精确内容。
- 部分提交内容未准备好或 changelist 不明确时直接报错，不回退到整文件 diff。
- Git 命令检查退出码，分别读取 stdout/stderr，保留空白；超时或取消时终止进程及子进程。
- 敏感路径、已识别的二进制文件以及超过 20,000 字节的未跟踪文件不读取正文；记录省略原因。
- 未跟踪的目录符号链接从其父目录定位仓库，再跳过目标读取；链接指向仓库外部时不会使其他文件的收集失败。
- 所有结构化 JSON 共用 100,000 字符预算，包含摘要、hunk、统计和省略信息。
- 优先保留安全文件摘要，然后分配完整 hunk；不从中间截断代码片段。
- 原始文件数量保持不变；增删行和 hunk 总数统计可读取的安全内容。模型输入标明裁剪情况。

插件提供有限的 diff 上下文，不自动读取完整相关源码、历史提交或项目 scope 约定。生成结果仍需用户检查后提交。

## 设置

| 字段 | 默认值 | 用途 |
| --- | --- | --- |
| Base URL | `https://api.deepseek.com/chat/completions` | 支持根地址、`/v1` 和完整聊天接口地址 |
| API Key | 空 | 保存到 IDEA PasswordSafe |
| Analysis Model | `deepseek-flash` | 分析结构化 diff，使用 API 模型 ID |
| Composer Model | `deepseek-flash` | 生成提交信息，使用 API 模型 ID |
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

HTTP 请求使用 Java 异步 HTTP 客户端，支持连接等待和流式订阅取消。默认 temperature 为 0.2，max_tokens 为 2048；官方 DeepSeek 请求使用非思考模式，避免思考流耗尽提交摘要的输出预算。SSE 忽略 `content: null`、缺失正文和思考片段，保留单独的空格及换行；token 截断时给出明确错误。拒绝无限增长的输出和过长 SSE 行；服务端错误正文不会直接显示到通知中。

`idea.log` 记录生成开始、所选文件数量、裁剪数量、完成或失败阶段，不记录 API Key、Prompt、源码或模型输出。

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

技术栈：Kotlin 2.0.21、Gradle 8.11、IntelliJ Platform Gradle Plugin 2.5.0；编译工具链为 JDK 17，目标 SDK 为 IDEA Community 2025.1。安装包声明支持 build 251.23774–262.*；已在 IDEA 262.8665.337 的实际提交面板验证所选变更收集、真实 DeepSeek 请求与中文结果写回。

```bash
cd idea-ai-commit-plugin
./gradlew test
./gradlew buildPlugin
```

测试包括真实临时 Git 仓库、首次提交、多仓库、重命名、部分内容、Git 进程取消、全局 JSON 预算、意图校验、重试反馈、配置迁移和提交框写回。网络测试使用本地 HTTP 服务验证 SSE、超时和取消，不调用真实模型接口。

安装包输出到 `idea-ai-commit-plugin/build/distributions/`。自动化测试不能替代实际 IDEA 提交面板和真实模型输出的人工验证。
