## Why

当前插件把 Git diff 直接拼进单次 Prompt，一次性请求模型生成 commit message。在大 diff、多文件、重构类改动下，模型同时承担「理解改动」与「写提交说明」两类任务，导致 `type/scope` 不稳定、重构易被误判为 `feat/fix`、subject 空洞，且本地仅有非空兜底，模型输出不稳定时质量无保障。需要将职责拆分为「意图归纳」与「文案生成」两阶段，并在中间插入本地规则纠偏与回退。

## What Changes

- 新增结构化 diff 构建：从勾选变更生成 `StructuredDiff`（文件摘要 + 关键 hunk + 预算裁剪 + 敏感文件过滤），替代整段原始 diff 直推。
- 新增第一阶段意图归纳：调用模型只输出结构化 `CommitIntent` JSON（type/scope 候选、intent、why、impact、confidence）。
- 新增本地规则打分层 `CommitRuleScorer`：基于文件分布自动纠偏 type（docs/test/refactor 等）、收敛 scope、标记低置信度。
- 新增第二阶段文案生成 `CommitMessageComposerService`：仅基于规整后的意图生成 Conventional Commits 文本。
- 新增结果校验器 `CommitMessageValidator`：格式校验、一次自动重试、低质量回退。
- **BREAKING**：`CommitGenerationRequest` 字段由 `diff/model/promptTemplate` 改为 `structuredDiff/analysisModel/composerModel/confidenceThreshold`，`GenerateCommitMessageAction` 同步切换。
- 扩展设置项：`analysisModel`、`composerModel`、`confidenceThreshold`、`maxFiles`、`maxHunksPerFile`，默认值安全可用，老配置自动兼容。
- `DeepSeekClient.streamChat` 增加 `systemPrompt` 参数，system/user 角色分离。

## Capabilities

### New Capabilities
- `commit-message-generation`: 两阶段提交说明生成全链路——结构化 diff 构建、意图归纳、本地规则纠偏、文案生成、校验重试与回退。

### Modified Capabilities
<!-- 无现有 spec，本期为全新引入 -->

## Impact

- **新增文件**：`model/StructuredDiff.kt`、`model/CommitIntent.kt`、`git/StructuredDiffBuilder.kt`、`llm/IntentAnalyzerClient.kt`、`service/CommitIntentAnalysisService.kt`、`service/CommitRuleScorer.kt`、`service/CommitMessageComposerService.kt`、`service/CommitMessageValidator.kt` 及对应测试。
- **修改文件**：`llm/DeepSeekClient.kt`（加 systemPrompt）、`prompt/CommitPromptBuilder.kt`（拆 analysis/composer 模板）、`service/CommitMessageGenerationService.kt`（重写为两阶段编排）、`action/GenerateCommitMessageAction.kt`（切结构化 diff + 新 Request + validator）、`settings/` 三件套（新字段 + UI + 校验）、`git/GitDiffCollector.kt`（新增 collectStructured）、`docs/README.md`。
- **依赖**：Jackson（IntelliJ Platform 已 bundled，无需新增）。
- **行为影响**：每次生成新增一次模型请求（耗时与成本上升），换取准确度与稳定性；任一阶段失败不污染用户原提交内容。
