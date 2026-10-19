## Context

插件现状为单阶段生成：`GenerateCommitMessageAction` 调 `GitDiffCollector.collectWithUnifiedDiff` 拿整段 raw diff，经 `CommitPromptBuilder` 拼单一 Prompt，由 `DeepSeekClient.streamChat`（无 system 角色）一次生成，最后 `ensureUsableMessage` 仅做非空校验。该链路在大 diff / 重构 / 多模块场景下 type 与 scope 不稳定、subject 易空洞，且模型返回异常时无有效兜底。

技术栈：Kotlin + IntelliJ Platform SDK + Git4Idea，测试 JUnit5 + MockK，JSON 用 IntelliJ bundled Jackson（`com.fasterxml.jackson.databind.ObjectMapper`，已在 `DeepSeekClient` 中使用）。

## Goals / Non-Goals

**Goals:**
- 拆分为「意图归纳 → 本地规则纠偏 → 文案生成 → 校验回退」四段，每段可独立测试。
- 结构化 diff：文件摘要 + 关键 hunk + 每文件预算 + 敏感过滤，取代整段直推。
- 本地规则收敛 type/scope，低置信度走保守策略。
- 保持 UI 入口与老配置兼容，不要求用户重新配置。

**Non-Goals:**
- 不做 PSI/AST 语义分析。
- 不学习 Git 历史风格。
- 不展示第一阶段 reasoning。
- 不改提交面板交互入口。

## Decisions

### D1：两阶段共用 `DeepSeekClient`，仅扩展 `systemPrompt` 参数
现状 `streamChat(baseUrl, apiKey, model, prompt, connectTimeout, readTimeout, onChunk)` 无 system 角色。改为 `streamChat(baseUrl, apiKey, model, prompt, systemPrompt: String? = null, connectTimeout, readTimeout, onChunk)`，当 `systemPrompt` 非空时在 messages 前插入 system 消息。
- **理由**：不重复造 HTTP/SSE 层；system/user 分离符合 spec 8.4.3。
- **替代方案**：新建独立 `IntentAnalyzerClient` 内部 HTTP → 拒绝，重复实现 SSE 解析。
- **注意**：改签名会破坏现有 `CommitMessageGenerationServiceTest` 的匿名 `override fun streamChat(...)`（旧签名无 systemPrompt），必须在改签名的同一批次内同步修该测试。

### D2：`CommitIntent` 字段加默认值以兼容 Jackson 反序列化
`objectMapper.readValue(raw, CommitIntent::class.java)` 要求无参构造或字段默认值。Kotlin data class 无默认值时 Jackson 抛 `InvalidDefinitionException`，且 build 未引入 `jackson-module-kotlin`。
- **决策**：给 `CommitIntent` 全字段默认值（`typeCandidates = emptyList()`、`confidence = 0.0` 等），避免引入新依赖。`ScoredCommitIntent` 仅被序列化（喂给第二阶段 prompt），无需默认值。

### D3：结构化 diff 的 hunk 提取与预算
`StructuredDiffBuilder.buildFromPatch` 接收 `List<PatchInput>`，按 `maxFiles` 取前 N 文件、每文件按 `maxHunksPerFile` 取前 N hunk、每 hunk 按 `limits.maxChars` 截断。敏感文件（`.env/.pem/.key/credentials`）整体过滤。`GitDiffCollector.collectStructured` 复用现有 `git diff HEAD --unified` 与未跟踪文件 patch 化逻辑，产出 `PatchInput` 列表。
- **替代方案**：全局截断 raw diff → 拒绝，破坏上下文完整性。

### D4：本地规则只做有限兜底，不强业务推断
`CommitRuleScorer` 规则：全 `.md` → docs；全 test 路径 → test；否则取模型首候选（合法集内）否则 refactor。scope 仅当模块不分散时保留。空洞 intent（`update code` 等）替换为通用描述。低置信度（`< confidenceThreshold`）打 `fallbackReason` 标记。
- **理由**：规则过强会覆盖模型正确判断（spec 14.2），仅做排序与有限兜底。

### D5：编排批次划分，规避中间态编译断裂
`CommitGenerationRequest` 字段是 BREAKING 改动，改后 `GenerateCommitMessageAction` 立即编译失败。故将「重写 `CommitMessageGenerationService`」与「改造 Action」并入同一批次（D），保证每批次 `./gradlew test` 可独立通过。

### D6：校验-重试-回退
`CommitMessageValidator.validateOrFallback`：空 → fallback；含 ```` ``` ```` 或 `#` 开头 → 拒绝；不匹配 Conventional Commits 正则 → 拒绝。重试由编排层在 composer 返回非法时再调一次 composer（prompt 收紧为「仅返回合法 commit message」）；仍失败用 `buildFallback` 生成 `type[(scope)]: intent`。

## Risks / Trade-offs

- **新增一次模型请求** → 耗时与成本上升。缓解：性能可接受（spec 12.1），换取准确度；后续可加并发或缓存。
- **本地规则过度修正** → 仅做有限兜底，通过测试样例校准（D4）。
- **改 `streamChat` 签名断旧测试** → 同批次内修 `CommitMessageGenerationServiceTest` override（D1）。
- **`CommitGenerationRequest` BREAKING 断 Action** → service 重写与 Action 改造同批次（D5）。
- **Jackson 反序列化失败** → `CommitIntent` 加默认值（D2）。
- **Action 遗留 `ensureUsableMessage` 调用** → 同步替换为 validator。

## Migration Plan

- 老配置无新字段时，`AiCommitSettingsState` 默认值兜底（analysisModel/composerModel 默认 `deepseek-chat`，confidenceThreshold `0.55`，maxFiles `12`，maxHunksPerFile `3`），无需迁移脚本。
- 回滚：单次 `git revert` 对应批次提交即可恢复单阶段链路（每批独立可编译）。

## Open Questions

- 重试时是否复用同一 `onChunk` 流式回填？默认：重试为静默聚合，不回填，避免 UI 闪烁。
