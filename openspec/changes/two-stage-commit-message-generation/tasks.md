# Implementation Tasks

> 实施方式：TDD（先写失败测试 → 实现 → 跑 `./gradlew test` → 通过后提交）。批次 A→E 按顺序执行，**每批必须能独立编译通过并跑通测试后再提交**。

## 1. 批次 A：数据层（无依赖，纯新增）

- [x] 1.1 新增 `model/StructuredDiff.kt`：`ChangedFileSummary`、`ChangeStatus`、`FileHunk`、`StructuredDiff`
- [x] 1.2 新增 `model/CommitIntent.kt`：`CommitIntent`（**全字段默认值**，兼容 Jackson 反序列化）、`ScoredCommitIntent`
- [x] 1.3 扩展 `settings/AiCommitSettingsState.kt`：新增 `analysisModel`、`composerModel`、`confidenceThreshold`、`maxFiles`、`maxHunksPerFile` 字段及安全默认值
- [x] 1.4 扩展 `settings/AiCommitSettingsService.validate`：覆盖新字段非法值（空模型、阈值越界、预算非正）
- [x] 1.5 新增 `git/StructuredDiffBuilder.kt`：`buildFromPatch`（敏感过滤、maxFiles、maxHunksPerFile、module/language 推断）+ `PatchInput`
- [x] 1.6 `git/GitDiffCollector.kt` 新增 `collectStructured`，复用 unified diff 与未跟踪 patch 逻辑产出 `StructuredDiff`
- [x] 1.7 新增测试 `git/StructuredDiffBuilderTest` + `CommitMessageGenerationServiceTest`（settings 默认值用例）
- [x] 1.8 跑 `./gradlew test` 通过后 commit：`feat: add two stage models and structured diff`

## 2. 批次 B：第一阶段意图归纳

- [x] 2.1 改 `llm/DeepSeekClient.streamChat`：在 `prompt` 后加 `systemPrompt: String? = null`，非空时插入 system 消息
- [x] 2.2 **同步修正**现有 `CommitMessageGenerationServiceTest` 匿名 `override fun streamChat` 签名（补 systemPrompt 参数），保证编译通过
- [x] 2.3 `prompt/CommitPromptBuilder.kt` 拆分 `buildAnalysisPrompt` / `buildComposerPrompt` 与对应 system 常量、模板
- [x] 2.4 新增 `llm/IntentAnalyzerClient.kt`：封装第一阶段调用，返回聚合 JSON
- [x] 2.5 新增 `service/CommitIntentAnalysisService.kt`：组装 prompt → 调 client → `readValue` 为 `CommitIntent` → 缺字段抛错
- [x] 2.6 新增测试 `service/CommitIntentAnalysisServiceTest`（合法 JSON 解析 + 缺字段失败）
- [x] 2.7 跑 `./gradlew test` 通过后 commit：`feat: add commit intent analysis stage`

## 3. 批次 C：本地规则 + 第二阶段 + 校验

- [x] 3.1 新增 `service/CommitRuleScorer.kt`：docs/test 强制、scope 收敛、空洞 intent 降级、低置信度标记
- [x] 3.2 新增测试 `service/CommitRuleScorerTest`（docs/test/refactor + 模块分散 + 低置信度）
- [x] 3.3 新增 `service/CommitMessageComposerService.kt`：基于 `ScoredCommitIntent` 流式生成 Conventional Commits 文本
- [x] 3.4 新增 `service/CommitMessageValidator.kt`：`validateOrFallback`（空→fallback、Markdown/代码块→拒绝、正则→拒绝）+ `buildFallback`
- [x] 3.5 新增测试 `service/CommitMessageComposerServiceTest` + `service/CommitMessageValidatorTest`
- [x] 3.6 跑 `./gradlew test` 通过后 commit：`feat: add rule scorer composer and validator`

## 4. 批次 D：编排串联（Request 字段 BREAKING，service 与 Action 必须同批）

- [ ] 4.1 改 `CommitGenerationRequest`：字段换为 `structuredDiff/analysisModel/composerModel/confidenceThreshold`（保留 connectTimeout/readTimeout）
- [ ] 4.2 重写 `CommitMessageGenerationService.generate`：analyze → score → compose（含一次重试） → validateOrFallback
- [ ] 4.3 改 `GenerateCommitMessageAction`：调 `collectStructured`、构造新 Request、**用 validator 替换 `ensureUsableMessage`**、保留异常恢复与锁释放
- [ ] 4.4 扩展 `settings/AiCommitSettingsConfigurable`：新增 Analysis Model / Composer Model / Confidence Threshold / Max Files / Max Hunks 字段
- [ ] 4.5 更新 `CommitMessageGenerationServiceTest`（mock 四段编排顺序）+ 新增 `GenerateCommitMessageActionTest`
- [ ] 4.6 跑 `./gradlew test` 通过后 commit：`refactor: orchestrate two stage commit generation`

## 5. 批次 E：文档与回归矩阵

- [ ] 5.1 `CommitMessageValidatorTest` 补 Markdown 拦截用例，收紧正则为合法 type 枚举集
- [ ] 5.2 `CommitRuleScorerTest` 补 docs/test/fix/refactor 全场景回归
- [ ] 5.3 更新 `docs/README.md`：两阶段架构图（GitDiffCollector → Analysis → Scorer → Composer → Validator → 回填）
- [ ] 5.4 跑全量 `./gradlew test` 确认 BUILD SUCCESSFUL
- [ ] 5.5 commit：`docs: update two stage commit generation flow`

## 6. 手工验证（runIde）

- [ ] 6.1 `./gradlew runIde`，提交面板触发 AI 生成，确认走新链路、设置页可见新字段
- [ ] 6.2 大 diff / 重构场景验证 type、scope、回退行为符合验收标准
