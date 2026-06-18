# Two-Stage Commit Message Generation OpenSpec

## 1. 概述

当前插件将 Git diff 直接拼进单次 Prompt，请求 DeepSeek 一次性生成 commit message。该方案在以下场景准确度不足：

- 大 diff 或多文件改动，模型容易抓错重点
- `type` 与 `scope` 选择不稳定
- 重构类改动容易被误判为 `feat` 或 `fix`
- 输出可能格式合法但语义空洞

本规范定义一套“两阶段生成”方案：先归纳变更意图，再生成 commit message，并在本地加入规则打分、校验、重试与回退策略。

## 2. 目标

### 2.1 功能目标

1. 将当前单阶段生成流程升级为两阶段：
   - 第一阶段：结构化分析变更意图
   - 第二阶段：基于意图生成 commit message
2. 提升 `type`、`scope`、`subject` 的准确度与稳定性。
3. 在大 diff、重构、多模块改动场景下，减少空洞和误导性提交说明。
4. 在模型返回不稳定时，仍能通过本地规则给出可用结果。

### 2.2 非目标

1. 不扩展为 code review、PR summary 或聊天工具窗。
2. 不引入多 VCS 支持，首期仍只支持 Git。
3. 不新增云端 Prompt 市场、远端规则同步。
4. 不改变提交面板交互入口，仍沿用现有 AI 按钮。

## 3. 术语

- **结构化 diff**：由文件摘要、关键 hunk、统计信息组成的分层输入，而非直接传整段原始 diff。
- **意图归纳**：模型对本次改动目的、影响范围、候选 `type/scope` 的结构化分析。
- **规则打分**：插件本地根据文件分布、路径特征、输出质量对意图结果进行自动修正与排序。
- **回退结果**：当模型结果无效或置信度过低时，插件本地生成的保守 commit message。

## 4. 用户价值

### 4.1 目标用户

- 已使用该插件生成 commit message 的 JetBrains IDE 用户
- 对 Conventional Commits 有规范要求的开发者
- 经常处理多文件、重构、跨模块变更的开发者

### 4.2 用户收益

1. commit message 更接近真实改动意图
2. `type` 与 `scope` 更稳定
3. 大改动下可读性更强
4. 接口偶发不稳定时不容易直接失败或写出垃圾结果

## 5. 当前问题

### 5.1 单次 Prompt 问题

1. 模型同时承担“理解改动”和“写 commit”两类任务，认知负担过重。
2. 原始 diff 过大时，模型容易被局部实现细节误导。
3. 直接截断 diff 会破坏上下文完整性，影响推理质量。

### 5.2 本地兜底不足

1. 没有针对 `type/scope` 的规则纠偏
2. 没有对空洞 subject 做明确拦截
3. 非法输出仅靠简单非空判断，质量门槛过低

## 6. 方案总览

新流程如下：

1. 从 IDE 当前勾选变更生成结构化 diff
2. 第一次调用模型，输出结构化意图 JSON
3. 使用本地规则对候选结果进行打分与修正
4. 第二次调用模型，仅基于归纳后的意图生成 commit message
5. 对输出做格式校验、必要时自动重试
6. 若仍失败，则回退到本地保守结果

## 7. 范围

### 7.1 本期纳入

1. 结构化 diff 构建
2. 两阶段 LLM 调用
3. 本地规则打分与纠偏
4. 结果校验、自动重试、回退
5. 配置项扩展与默认值设计
6. 测试与 README 同步更新

### 7.2 本期不纳入

1. 基于 IDE PSI/AST 的语义分析
2. 基于 Git 历史学习团队 commit 风格
3. 让用户参与候选排序或手动打分
4. 额外展示第一阶段 reasoning 结果

## 8. 详细需求

### 8.1 结构化 diff

插件必须将当前勾选变更转为结构化输入，而不是直接将完整原始 diff 无差别发送给模型。

#### 8.1.1 文件摘要

每个变更文件至少包含：

- 相对路径
- 状态：`added` / `modified` / `deleted` / `renamed`
- 增加行数
- 删除行数
- 文件扩展名
- 推断语言

#### 8.1.2 关键 hunk

每个文件应提取有限数量的关键 hunk：

- 默认优先保留前若干个 hunk
- 每文件应有独立预算
- 超长文件仅保留高价值上下文，不允许简单全局截断

#### 8.1.3 安全与过滤

以下文件默认不发送给模型：

- `.env`
- `*.pem`
- `*.key`
- `credentials*.json`
- 其他显式敏感配置文件

二进制文件必须跳过。未跟踪文件必须标准化为相对路径 patch，不得使用绝对路径。

### 8.2 第一阶段：意图归纳

第一阶段模型调用仅负责理解改动，不直接生成 commit message。

#### 8.2.1 输入

- 结构化 diff
- 输出语言
- 固定 JSON 输出约束

#### 8.2.2 输出

必须返回可反序列化 JSON，至少包含：

- `typeCandidates`
- `scopeCandidates`
- `intent`
- `why`
- `impact`
- `confidence`

#### 8.2.3 失败处理

若第一阶段返回非 JSON、空 JSON、关键字段缺失，视为失败，不得当作成功继续进入第二阶段。

### 8.3 本地规则打分

插件必须在第一阶段与第二阶段之间插入本地规则层，用于自动修正与排序，而不是依赖用户打分。

#### 8.3.1 type 规则

至少覆盖以下情形：

- 仅文档文件为主：优先 `docs`
- 仅测试文件为主：优先 `test`
- 明显新增能力：优先 `feat`
- 明显错误修复：优先 `fix`
- 跨模块结构调整且无明显新功能：优先 `refactor`

#### 8.3.2 scope 规则

- 当多数改动集中在同一模块目录时，允许保留 scope
- 当改动分散在多个模块时，应优先省略 scope

#### 8.3.3 质量规则

以下意图描述应降分或否决：

- `update code`
- `modify files`
- `optimize logic`
- 其他缺少业务语义的空洞表述

#### 8.3.4 低置信度处理

当 `confidence` 低于阈值时，应进入保守策略：

- 降低 scope 使用概率
- 收紧 body 生成条件
- 允许最终回退到通用但正确的提交说明

### 8.4 第二阶段：commit message 生成

第二阶段模型调用只负责根据归纳后的意图生成最终 commit message。

#### 8.4.1 输入

- 经规则层修正后的意图对象
- 输出语言
- Conventional Commits 约束

#### 8.4.2 输出要求

1. 默认只输出 `subject`
2. 仅在 `why/impact` 明显不足以被 subject 表达时，允许带 body
3. 不允许输出解释性文本、分析性文本、Markdown 代码块

#### 8.4.3 Prompt 约束

- 第一阶段与第二阶段应拆分为独立 Prompt
- 第二阶段不应再携带完整大 diff
- system 与 user 角色分离，避免规则与上下文混写

### 8.5 结果校验与重试

在最终结果写回提交框前，插件必须进行本地校验。

#### 8.5.1 校验项

- 是否符合 Conventional Commits 基本格式
- subject 是否为空
- subject 是否超长
- 是否包含代码块、Markdown 标记或明显废话

#### 8.5.2 重试策略

- 首次失败后允许自动重试一次
- 重试 Prompt 只允许要求“仅返回合法 commit message”
- 若重试仍失败，必须走回退逻辑

#### 8.5.3 回退逻辑

回退结果应优先保证“正确、保守、可提交”，而非追求表达丰富。

示例：

- `docs: update plugin documentation`
- `test: refine commit generation tests`
- `refactor: reorganize commit message pipeline`

## 9. 架构设计

### 9.1 模块拆分

#### `git/StructuredDiffBuilder`

职责：

- 从当前勾选变更构建结构化 diff
- 提供每文件摘要、关键 hunk、预算裁剪、敏感信息过滤

不负责：

- LLM 调用
- UI 写回

#### `llm/IntentAnalyzerClient`

职责：

- 调用第一阶段模型
- 解析并返回结构化意图

不负责：

- 最终 commit message 生成
- 本地规则打分

#### `service/CommitRuleScorer`

职责：

- 根据文件分布、路径特征、候选类型与置信度做自动纠偏
- 输出可供第二阶段消费的标准意图对象

不负责：

- 网络调用
- UI 交互

#### `service/CommitMessageComposerService`

职责：

- 基于规则层输出生成最终 commit message

#### `service/CommitMessageValidator`

职责：

- 校验最终输出
- 控制自动重试
- 触发回退结果

### 9.2 编排层

现有 `CommitMessageGenerationService` 应拆分为分析与生成两个阶段的编排职责，避免继续以单次 Prompt 为核心。

`GenerateCommitMessageAction` 仍负责：

- 读取 IDE 上下文
- 控制生成状态
- 回填 Commit Message
- 异常时恢复原输入

## 10. 数据契约

### 10.1 StructuredDiff

建议字段：

- `files`
- `keyHunks`
- `truncated`
- `totalFiles`
- `totalAdditions`
- `totalDeletions`

### 10.2 CommitIntent

建议字段：

- `typeCandidates`
- `scopeCandidates`
- `intent`
- `why`
- `impact`
- `confidence`

### 10.3 ScoredCommitIntent

建议字段：

- `finalType`
- `finalScope`
- `normalizedIntent`
- `why`
- `impact`
- `confidence`
- `fallbackReason`

## 11. 配置设计

在现有设置页基础上，新增但保持默认可用：

- 第一阶段模型名（默认继承现有模型）
- 第二阶段模型名（默认继承现有模型）
- `confidenceThreshold`
- `maxFiles`
- `maxHunksPerFile`

若用户未显式配置，插件必须使用安全默认值，不要求升级用户重新配置。

## 12. 性能与稳定性要求

### 12.1 性能

1. 点击 AI 按钮后必须快速进入生成中状态
2. 结构化 diff 构建必须在后台线程执行
3. 两阶段模型调用增加后，总体响应时间虽可上升，但不应牺牲 UI 可用性

### 12.2 稳定性

1. 任一阶段失败都不得污染用户原提交内容
2. 任一阶段异常都必须释放生成锁
3. 重试与回退路径必须可测试、可重复

### 12.3 安全

1. 不记录 API Key
2. 不向日志输出原始敏感 diff 内容
3. 默认过滤敏感文件内容

## 13. 验收标准

以下场景应满足预期：

1. 仅修改文档文件时，结果优先为 `docs: ...`
2. 仅修改测试文件时，结果优先为 `test: ...`
3. 明显修复 bug 时，结果优先为 `fix: ...`
4. 明显新增功能入口时，结果优先为 `feat: ...`
5. 跨目录重构时，结果优先为 `refactor: ...`
6. 改动分散时，不应滥用 scope
7. 大 diff 场景下，不应因硬截断产生明显错误 subject
8. 模型返回非规范文本时，不应直接写入提交框

## 14. 风险与权衡

### 14.1 新增一次模型请求

代价：

- 平均耗时上升
- 请求成本上升

收益：

- 显著降低一次性大 Prompt 误判
- 更适合后续扩展 review / summary 场景

### 14.2 本地规则过强

风险：

- 可能把模型正确判断过度修正

处理方式：

- 规则层只做有限兜底与排序，不做复杂业务推断
- 通过测试样例持续校准阈值

## 15. 里程碑

### M1

- 完成结构化 diff 与数据模型

### M2

- 完成第一阶段意图归纳与 JSON 解析

### M3

- 完成本地规则打分与第二阶段生成

### M4

- 完成结果校验、重试、回退

### M5

- 完成 UI 编排切换、测试补齐、文档更新

## 16. 向后兼容

1. 保留现有 AI 按钮入口与设置页入口
2. 老配置默认自动迁移到新流程
3. 若新增参数未配置，使用默认值继续工作

## 17. 决策结论

本期采用“两阶段生成 + 本地规则兜底”的方案，而不继续增强单次 Prompt。理由如下：

1. 准确度问题核心在于职责混杂，不在于单纯补几条 Prompt 规则
2. 两阶段结构更利于后续扩展、调试、测试与灰度优化
3. 本地规则层可以显著降低 `type/scope` 误用率

