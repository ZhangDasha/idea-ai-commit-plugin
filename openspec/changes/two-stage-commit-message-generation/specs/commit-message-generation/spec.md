## ADDED Requirements

### Requirement: 结构化 diff 构建
系统 SHALL 将当前勾选变更转为结构化输入 `StructuredDiff`，包含文件摘要、关键 hunk、统计信息，而非整段原始 diff。

#### Scenario: 跳过敏感文件
- **WHEN** 变更包含 `.env` 文件时
- **THEN** 该文件不出现在 `StructuredDiff.files` 与 `keyHunks` 中

#### Scenario: 每文件 hunk 预算
- **WHEN** 单文件 hunk 数超过 `maxHunksPerFile` 时
- **THEN** 仅保留前 `maxHunksPerFile` 个 hunk

#### Scenario: 文件数量预算
- **WHEN** 变更文件数超过 `maxFiles` 时
- **THEN** 仅保留前 `maxFiles` 个文件且 `truncated` 为 true

#### Scenario: 文件摘要字段完整
- **WHEN** 构建结构化 diff 时
- **THEN** 每个文件摘要 SHALL 包含相对路径、状态、增删行数、扩展名、推断语言、模块

### Requirement: 第一阶段意图归纳
系统 SHALL 调用模型仅输出可反序列化的 `CommitIntent` JSON，包含 `typeCandidates`、`scopeCandidates`、`intent`、`why`、`impact`、`confidence`。

#### Scenario: 解析合法 JSON
- **WHEN** 模型返回合法 intent JSON 时
- **THEN** 系统返回字段完整的 `CommitIntent` 对象

#### Scenario: 拒绝非 JSON 或缺字段
- **WHEN** 模型返回非 JSON、空 JSON 或 `typeCandidates` 缺失时
- **THEN** 视为第一阶段失败，不进入第二阶段

#### Scenario: system 与 user 角色分离
- **WHEN** 发起第一阶段请求时
- **THEN** system 角色仅约束输出格式，user 角色携带结构化 diff

### Requirement: 本地规则打分与纠偏
系统 SHALL 在两阶段之间插入本地规则层，自动修正 type、收敛 scope、标记低置信度，不依赖用户打分。

#### Scenario: 仅文档改动强制 docs
- **WHEN** 所有变更文件均为 `.md` 时
- **THEN** `finalType` 为 docs 且 `normalizedIntent` 为文档相关描述

#### Scenario: 仅测试改动强制 test
- **WHEN** 所有变更文件均位于 test 路径时
- **THEN** `finalType` 为 test

#### Scenario: 模块分散时省略 scope
- **WHEN** 变更分布在多个不同模块时
- **THEN** `finalScope` 为 null

#### Scenario: 低置信度标记
- **WHEN** `confidence` 低于 `confidenceThreshold` 时
- **THEN** `fallbackReason` 标记低置信度

#### Scenario: 空洞意图降级
- **WHEN** intent 为 `update code` 等空洞表述时
- **THEN** `normalizedIntent` 替换为通用描述

### Requirement: 第二阶段文案生成
系统 SHALL 仅基于规则层修正后的意图生成 Conventional Commits 文本，第二阶段不携带完整原始 diff。

#### Scenario: 流式生成 subject
- **WHEN** 第二阶段收到 `ScoredCommitIntent` 时
- **THEN** 返回 Conventional Commits 格式文本并通过 onChunk 流式回调

### Requirement: 结果校验、重试与回退
系统 SHALL 在写回提交框前进行本地校验，必要时自动重试一次，仍失败则回退到保守结果。

#### Scenario: 空输出回退
- **WHEN** 生成结果为空或空白时
- **THEN** 返回基于 `ScoredCommitIntent` 的保守 `type[(scope)]: intent`

#### Scenario: 拒绝非提交文本
- **WHEN** 结果含代码块或 Markdown 标记时
- **THEN** 拒绝并触发重试或回退

#### Scenario: 拒绝非法格式
- **WHEN** 结果不匹配 Conventional Commits 基本格式时
- **THEN** 拒绝并触发重试或回退

#### Scenario: 重试仅一次
- **WHEN** 首次校验失败时
- **THEN** 至多重试一次，重试 prompt 仅要求返回合法 commit message

### Requirement: 配置扩展与兼容
系统 SHALL 新增两阶段相关配置项且默认值安全可用，老配置无需手动迁移。

#### Scenario: 默认值可用
- **WHEN** 用户未显式配置新字段时
- **THEN** analysisModel 与 composerModel 默认 `deepseek-chat`、confidenceThreshold 默认 0.55、maxFiles 默认 12、maxHunksPerFile 默认 3

#### Scenario: 校验新配置
- **WHEN** 新字段为非法值（空模型、阈值越界、预算非正）时
- **THEN** 配置校验返回错误描述

### Requirement: 稳定性与安全
系统 SHALL 保证任一阶段失败不污染用户原提交内容、释放生成锁，且不记录敏感信息。

#### Scenario: 异常恢复原输入
- **WHEN** 任一阶段抛异常时
- **THEN** 提交框恢复为用户原始内容并释放生成锁

#### Scenario: 敏感内容不入日志
- **WHEN** 生成过程记录日志时
- **THEN** 不输出 API Key 与敏感 diff 内容
