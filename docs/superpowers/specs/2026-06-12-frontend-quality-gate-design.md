# AI Commit 前端质量闸口设计

## 1. 目标

在现有 `IDEA AI Commit` 插件基础上，新增一套可复用的前端质量闸口能力。

本期只接入 `获取 AI Commit` 入口：

- 点击 `获取 AI Commit` 前，自动识别本次勾选变更命中的前端项目
- 对命中项目执行 `TypeScript`、`ESLint`、项目自定义脚本检查
- 发现严重问题时弹窗提示，允许用户二次确认后继续生成 AI commit
- 发现警告时不阻断生成，并将精简警告摘要附加到模型提示词

后续版本再规划接入 `Commit / Commit and Push` 的 `拦截 / 仅提示` 可配置能力。

## 2. 本期范围

### 2.1 纳入

1. 在插件设置中增加“前端检查”总开关。
2. 在 `获取 AI Commit` 前增加前端项目识别与检查流程。
3. 支持自动识别 `TypeScript`、`ESLint` 常见脚本。
4. 支持按项目根目录手动补充 `TypeScript`、`ESLint`、自定义脚本命令。
5. 同一项目内并行执行检查项，并受全局并发上限控制后汇总统一结果。
6. 严重问题弹窗：摘要展示、详情展开、继续生成/取消生成/打开设置。
7. 仅在存在警告时，将精简摘要附加到 AI prompt。
8. 明确外部检查进程的生命周期与资源回收策略，避免常驻与僵尸进程。

### 2.2 不纳入

1. `Commit / Commit and Push` 入口拦截。
2. 自动修复问题或执行 `--fix`。
3. 非前端项目检查，如 Maven、Gradle、Python。
4. 增量 lint、按文件级别拼接命令。
5. 项目级 UI 配置编辑器的复杂管理能力，如导入导出、批量复制。

## 3. 用户体验

### 3.1 主流程

1. 用户在提交面板勾选若干变更文件。
2. 用户点击 `获取 AI Commit`。
3. 插件判断“前端检查”总开关是否开启。
4. 若关闭，直接进入既有 AI commit 生成流程。
5. 若开启，插件识别本次勾选变更命中的前端项目。
6. 若未命中前端项目，直接进入既有 AI commit 生成流程。
7. 若命中前端项目，插件执行检查并汇总结果。
8. 若仅有警告，直接生成 AI commit，并把警告摘要附加到 prompt。
9. 若存在严重问题，弹出异常提示框，由用户选择：
   - `取消生成`
   - `继续生成`
   - `打开设置`

### 3.2 异常交互

- 命令未识别：
  - 仅提示，不阻断
- 自定义脚本失败：
  - 仅提示，不阻断
- 总超时命中：
  - 未完成检查记为警告，不阻断
- 严重问题命中：
  - 弹窗确认，用户可继续生成

## 4. 前端项目识别规则

只有同时满足以下条件，才认定为本次需要检查的前端项目：

1. 本次勾选变更中存在前端文件：
   - `.js`
   - `.jsx`
   - `.ts`
   - `.tsx`
   - `.vue`
2. 从变更文件向上可定位到项目根目录，且存在 `package.json`
3. 项目根目录或其约定层级内存在以下任一配置：
   - `tsconfig.json`
   - `tsconfig.*.json`
   - `eslint.config.js|cjs|mjs|ts`
   - `.eslintrc`
   - `.eslintrc.js|cjs|json|yaml|yml`

多项目场景下，仅检查本次勾选变更实际命中的所有前端项目。

## 5. 检查项与严重级别

### 5.1 检查项

每个命中项目执行三类检查：

1. `TypeScript`
2. `ESLint`
3. 项目自定义脚本

### 5.2 严重级别规则

- 严重问题：
  - `TypeScript` 检查失败
  - `ESLint error` 检查失败
- 警告：
  - 项目自定义脚本失败
  - 未识别到 `TypeScript` 命令
  - 未识别到 `ESLint` 命令
  - 检查超时

本期不把 warning 级别 ESLint 输出单独做语义解析；以命令退出结果为主，配合命令命名与项目配置做归类。

## 6. 命令解析策略

### 6.1 自动识别

优先从项目 `package.json` 中识别常见脚本名：

- `TypeScript`
  - `ts:check`
  - `typecheck`
  - `tsc`
- `ESLint`
  - `lint`
  - `lint:eslint`
  - `eslint`

自动识别命中后，优先执行脚本命令。

### 6.2 手动补充

若自动识别不到，则读取插件设置中的项目级配置：

- `typescriptCommand`
- `eslintCommand`
- `customCommands[]`

项目级配置以“项目根目录”作为唯一 key。

### 6.3 未识别处理

若 `TypeScript` 或 `ESLint` 最终都未识别到命令：

- 记录为警告
- 在检查摘要中展示
- 不阻断本次 AI commit 生成

## 7. 执行策略

### 7.1 并行模型

- 同一项目内：
  - `TypeScript`
  - `ESLint`
  - `customCommands`
  逻辑上并行执行
- 多项目之间：
  - 逻辑上并行执行

实现层必须增加统一并发闸门，避免同时拉起过多 Node 进程：

- 全局最大外部进程并发数默认 `2`
- 单项目同时运行的外部进程数默认 `2`
- 若命中多个前端项目，超出上限的检查项进入等待队列
- 并发上限本期先写死在实现常量中，不在设置页暴露

这样保留“检查项可并行”的产品语义，同时避免在 `IntelliJ IDEA 2026.1.2` 下因多个 `tsc` / `eslint` / 自定义脚本同时启动而造成内存尖峰和 IDE 卡顿。

### 7.2 超时模型

- 本期只配置一个总超时 `checkTimeoutMillis`
- 超时到达后：
  - 中断未完成任务
  - 主动销毁对应外部进程树
  - 将未完成检查项记为警告
  - 保留已完成结果

若用户关闭项目、取消任务或插件卸载，也必须走同样的进程终止逻辑。

### 7.3 输出采样

为避免弹窗过载，命令结果统一保留：

- 命令文本
- 工作目录
- 退出码
- 耗时
- 截断后的关键输出
- 详情面板使用的完整截断输出

同时增加内存保护约束：

- 单个检查项内存中保留的输出上限默认 `64 KB`
- 用于摘要提取的关键输出上限默认 `8 KB`
- 超过上限后立即截断，并在详情中标记 `output truncated`
- 不在内存中长期缓存完整 stdout/stderr

## 8. AI Prompt 附加规则

仅在存在警告时，向模型补充一段精简摘要。

示例：

```text
附加上下文（仅供参考，不必在提交说明中直接体现）：
- 项目 web-pc: 未识别到 TypeScript 检查命令
- 项目 web-h5: 自定义检查脚本失败，请避免将提交说明表述为“已完全通过本地校验”
```

若不存在警告，则保持当前 prompt 行为不变。

## 9. 架构设计

### 9.1 模块划分

1. `GenerateCommitMessageAction`
   - 现有 AI 按钮入口
   - 新增质量闸口编排调用
2. `FrontendQualityGateService`
   - 本期核心编排层
   - 负责项目识别、命令解析、执行、结果汇总
3. `FrontendProjectDetector`
   - 从勾选变更反推项目根目录
   - 判断是否命中前端项目
4. `FrontendCheckCommandResolver`
   - 自动识别或读取项目级手动配置
5. `FrontendCheckRunner`
   - 执行命令、处理超时、采样输出
6. `QualityGateDialog`
   - 摘要展示 + 详情展开 + 用户决策

### 9.2 职责边界

- Action 只处理入口流程和用户交互结果，不负责命令识别细节
- QualityGateService 输出统一结果对象，不直接操作 AI prompt 拼装逻辑
- CommandResolver 只产生命令定义，不执行命令
- CheckRunner 只执行命令，不判断严重级别
- Dialog 只消费汇总结果，不关心底层检测过程
- 持有运行中进程句柄、等待队列、取消信号的对象必须挂在项目级生命周期，不放在 application 级单例里常驻

## 9.3 生命周期与常驻约束

- 所有外部检查命令均为一次性短任务，不允许以 `--watch`、daemon、server 模式启动
- 不复用 Node 子进程做常驻检查池
- 项目关闭、任务取消、插件卸载时，必须释放：
  - 运行中进程句柄
  - 输出缓冲区
  - Future / Job / 回调引用
- 弹窗、监听器、后台任务引用必须绑定可释放父级，避免跨项目残留

## 10. 数据模型

### 10.1 配置模型

#### 全局配置

- `enableFrontendChecks: Boolean`
- `checkTimeoutMillis: Int`
- `includeWarningSummaryInPrompt: Boolean`

#### 项目级配置

- `projectRoot: String`
- `typescriptCommand: String?`
- `eslintCommand: String?`
- `customCommands: List<String>`

说明：

- 项目级配置只保存命令文本与项目根路径，不保存运行结果缓存
- 最近一次检查结果只保留在本次动作上下文中，动作结束后释放

### 10.2 结果模型

#### `FrontendCheckSessionResult`

- `projects: List<FrontendProjectCheckResult>`
- `hasCritical: Boolean`
- `hasWarnings: Boolean`
- `warningSummaryForPrompt: String?`
- `requiresConfirmation: Boolean`

#### `FrontendProjectCheckResult`

- `projectRoot: String`
- `projectName: String`
- `checks: List<FrontendCheckResult>`

#### `FrontendCheckResult`

- `type: FrontendCheckType`
- `command: String?`
- `status: FrontendCheckStatus`
- `exitCode: Int?`
- `durationMillis: Long`
- `summary: String`
- `details: String`

#### 枚举

- `FrontendCheckType`
  - `TYPESCRIPT`
  - `ESLINT`
  - `CUSTOM`
- `FrontendCheckStatus`
  - `PASSED`
  - `WARNING`
  - `CRITICAL`

## 11. 弹窗设计

### 11.1 默认摘要

默认显示：

- 失败项目名
- 失败检查项类型
- 一行关键原因

### 11.2 展开详情

展开后显示：

- 执行命令
- 工作目录
- 退出码
- 关键报错片段

### 11.3 操作按钮

- `取消生成`
- `继续生成`
- `打开设置`

若用户选择 `继续生成`，则进入既有 AI commit 生成流程。

## 12. 与现有生成流程的集成

`GenerateCommitMessageAction` 新流程：

1. 读取设置与勾选变更
2. 若检查关闭，直接生成
3. 执行 `FrontendQualityGateService`
4. 若仅警告：
   - 生成附加摘要
   - 继续调用 `CommitMessageGenerationService`
5. 若有严重问题：
   - 弹出 `QualityGateDialog`
   - 用户确认后再调用 `CommitMessageGenerationService`

建议将“检查摘要附加到 prompt”的逻辑放在 `CommitGenerationRequest` 上新增可选字段，避免直接拼接已有 diff 文本。

## 13. 测试策略

### 13.1 单元测试

1. `FrontendProjectDetector`
   - 命中前端项目
   - 缺少 `package.json`
   - 缺少 `tsconfig/eslint` 配置
   - 多项目识别
2. `FrontendCheckCommandResolver`
   - 自动识别成功
   - 回退到项目级配置
   - 未识别命令记警告
3. `FrontendQualityGateService`
   - 全通过
   - 仅警告
   - 含严重问题
   - 总超时命中
4. `Prompt` 附加摘要
   - 无警告不附加
   - 有警告才附加

### 13.2 集成验收

1. 勾选纯后端文件时，不触发前端检查。
2. 勾选前端项目文件且检查通过时，直接生成 AI commit。
3. 勾选前端项目文件且 `TypeScript` 失败时，弹窗提示，可继续或取消。
4. 勾选多前端项目文件时，能看到汇总结果。
5. 自动识别不到命令时，仅提示，不阻断。
6. 关闭总开关后，行为回退为当前版本。

## 14. 后续演进

后续版本可在当前内核基础上扩展：

1. 接入 `Commit / Commit and Push` 前置检查
2. 支持 `拦截 / 仅提示 / 忽略` 分级配置
3. 支持项目级超时配置
4. 支持更多脚本自动发现策略
5. 支持更细粒度的 ESLint warning/error 解析

## 15. 版本兼容与验证策略

### 15.1 当前优先验证平台

本期实现与验收优先以用户当前环境为准：

- `IntelliJ IDEA 2026.1.2`
- build `IU-261.24374.151`
- JetBrains Runtime `25.0.2`

### 15.2 兼容性约束

- 本期新增功能不得依赖仅在 `261` 之后才存在的 API
- 若实现阶段使用了 `261` 独有 API，则需要：
  - 收窄插件支持范围
  - 或拆分独立发布版本
- 若继续保留跨 `241 -> 261` 的兼容声明，则必须补充 Plugin Verifier 验证矩阵

### 15.3 线程与后台任务约束

- 不在 EDT 上执行文件遍历、进程等待、日志聚合、命令解析
- 后台任务必须支持取消，并在取消时尽快停止资源占用
- 若实现使用 IntelliJ 2024.1+ 推荐方式，可优先考虑 coroutine + cancellation；若沿用现有 `Task.Backgroundable`，也必须补齐取消与资源回收
