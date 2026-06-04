# Codex 开发验证护栏方案

本文档将验证讨论转化为 Codex 协助开发时的轻量证据护栏。它不是独立开发平台，
也不是替代 Codex 的自动工程系统。`docs/architecture-design.md` 仍然是 Lynx
运行时架构的唯一真相源。

本文档的默认目标是约束 Codex 在本仓库内进行架构演进、实验验证和方向审查：
让 Codex 更少偏航，让用户只参与目标、阈值、安全和少量代表性权衡。

一句话定位：这套护栏给 Codex 开发过程装方向盘、刹车和仪表盘，而不是再造一辆车。

优先级：

1. 先服务 Lynx 当前完成率、假完成防护、恢复能力和少用户参与目标
2. 再约束 Codex 开发过程不偏离 `docs/architecture-design.md`
3. 用来约束 Codex 的 trace、oracle、metrics、gate、benchmark 和 report 必须先自证准确
4. 严禁用业务 app 硬编码、页面文案特判、固定点击路径或一次性补丁刷完成率
5. 然后从 Lynx 的有效实践中沉淀可迁移的护栏协议、schema、清单和报告格式
6. 最后才考虑工具化、跨项目 adapter 或独立平台

## 0. 设计总览

Codex 开发验证护栏的目标不是证明某次 demo 成功，也不是建设一个新的工程平台，
而是让 Codex 在用户无法持续盯细节的情况下，仍然持续朝更高真实完成率演进。

核心闭环：

```text
Architecture / Scenario 定义目标
  -> Codex 提出假设并做小范围改动
  -> 测试、Trace 或 Benchmark 产生证据
  -> Oracle / Verifier 判定完成
  -> Metrics 计算完成率、虚假成功率、恢复成功率、用户参与成本和成本
  -> Acceptance Gate 决定保留、拒绝或升级
  -> Failure Review 形成下一轮 Codex 假设
  -> 通过后形成候选基线或下一步建议
```

设计原则：

1. **完成率优先，但必须是经验证的完成率**：模型自称完成、页面刚变化或最近点击
   成功都不能算成功。
2. **防止 Codex 迭代偏航**：Codex 可以自主提出假设、改代码、跑实验和生成报告，但
   只能在验收门内保留变更。
3. **护栏先自证可信**：trace、判定器、指标、验收门和基线都必须有自检机制。
4. **工具准确性优先于自动化速度**：任何用于约束 Codex 决策的工具、指标、判定器、
   benchmark 或报告，未通过自检前只能作为参考，不能用于保留变更、晋升基线或判断
   方向正确。
5. **用户只参与方向控制**：用户定义目标、验收阈值、安全规则和少量代表性审查；
   不需要逐条审 prompt 或 packet 字段。
6. **先本地事实源，再接外部工具**：本地 JSONL artifact 是真相源，Langfuse、
   LangWatch、Phoenix 等只能作为观测或展示适配层。
7. **第一版服务本仓库，长期沉淀协议**：可复用是长期目标，但第一阶段只沉淀
   schema、清单、验收规则和报告格式，不主动建设跨项目 adapter 或完整 AgentLab
   工具链。
8. **禁止业务硬编码提升**：agent runtime 不能因为某个 benchmark、某个 app、某个
   页面文案或某个业务流程而写特殊分支。业务知识只能放在 scenario、oracle、
   fixture、人工标注或评估报告里，用来衡量能力，不能成为执行能力本身。
9. **视觉优先、上下文最小化**：Lynx 的主能力应来自模型对截图和任务目标的理解，
   默认执行路径是视觉输入加坐标动作。XML、UI tree、OCR 和候选项可以用于记录、
   回放、oracle、调试和消融实验，但不能未经验证就变成默认模型上下文、模型可见
   动作句柄或主执行路径。

可信边界：

```text
护栏可信 = 工具准确 + trace 完整 + oracle 校准 + 指标正确 + gate 正确 + baseline 健康
```

任何一项不成立，Codex 自主迭代必须暂停，不能继续建议基线晋升。

## 1. 目标

护栏的存在目的是提升真实任务完成率，同时防止 Codex 主导的开发偏离用户预期方向。

主要目标：

1. 提升经验证的完成率
2. 降低虚假成功率
3. 在回归成为产品方向之前检测到它
4. 让 Codex 承担绝大部分实现、实验和分析工作
5. 让人类只负责目标、验收规则和少量代表性审查决策

这不是传统的应用测试套件，也不是独立开发平台，而是一个约束 Codex 开发过程的
实验与证据框架。

### 1.1 业务硬编码边界

本仓库的目标是提升 Lynx Agent 的通用手机任务能力。任何变更都必须能解释为改进了
观察、模型上下文、规划、执行、工具、恢复、验证、trace 或 benchmark 可信度，而不是只让
某个业务场景通过。

允许出现在评估侧：

1. `benchmarks/scenarios` 或 suite 中的 app、起始状态、账号 fixture 和成功标准
2. 外部 deterministic oracle 对某个场景的成功判定
3. labeled examples 和人工审查记录
4. 用来复现失败的 fixture、trace 和报告

禁止进入 agent runtime：

1. 针对某个 app、页面、按钮文案、联系人、商品、设置项或固定路径的特殊分支
2. 为单个 benchmark 写的完成判定、跳转策略、点击脚本、坐标补丁或 prompt 特判
3. 把业务场景知识写进 `ExecutorAgent`、`PlannerAgent`、`VerifierAgent`、
   `CompletionGate`、`RecoveryPolicy` 或 primitive tool 的通用逻辑
4. 任何无法迁移到同类任务的补丁式完成率提升

如果某个候选变更的收益主要来自业务硬编码，该候选必须拒绝；如果只是用业务场景暴露
了通用能力缺陷，则应修通用能力，并把业务知识留在评估侧。

### 1.2 模型理解与上下文边界

本护栏的下一阶段目标是提升模型理解能力，而不是把手机任务拆成业务脚本、XML 规则或
候选项自动化。

默认接受的方向：

1. 让执行 packet 更短、更清楚、更贴近模型已经擅长的视觉理解
2. 保留截图和坐标动作为主路径
3. 把最近一次动作、结构化结果、verifier missing evidence 和 recovery hint 清楚传给模型
4. 通过 trace 和 benchmark 判断某个上下文字段是否真的有帮助
5. 让 XML、UI tree、OCR 和候选项主要服务内部记录、失败分析、oracle 和离线消融
6. 坐标工具与截图保持同一个坐标系：默认使用 0-1000 归一化坐标，不使用设备像素
   坐标，也不允许 runtime 静默猜测两种坐标系

默认拒绝的方向：

1. 把 raw XML、完整 UI tree 或长候选列表直接塞进默认 prompt
2. 强制模型优先使用候选句柄，并因此压制合法坐标点击
3. 为了让结构化系统看起来更完整，增加模型难以消化的输入噪声
4. 没有 A/B 证据就把某个结构化观察通道提升为默认输入
5. 用 app、页面或文案特判弥补模型理解失败
6. 让工具提示说一种坐标系、工具执行按另一种坐标系换算

上下文是产品能力的一部分。任何新增上下文字段都必须能回答：

1. 它帮助模型看清了什么？
2. 它是否减少错误动作或重复动作？
3. 它是否增加 token、延迟、空响应或注意力偏移？
4. 它是否会让模型忽视截图本身？
5. 它是否通过 `status=success && trace_integrity=pass` 的对照验证？

## 2. 运行模型

用户不需要检查每条 prompt、每个 packet 字段、每条启发式规则或每个运行时补丁。
护栏应当让每次变更都可度量，使用户可以只审查方向级别的结果。

默认循环：

1. Codex 提出假设。
2. Codex 做一个小范围的实现变更。
3. Codex 运行可用的测试、回放、基准测试或消融实验。
4. 护栏将结果与基线或上一轮结果进行比较。
5. 护栏根据验收规则拒绝、保留为候选变更或升级该变更。
6. 失败或模糊的用例被加入回归集。
7. 用户只审查高影响权衡、代表性失败和验收规则变更。

护栏应当让"不偏离"成为 Codex 开发过程的运行时约束，而不是靠用户的耐心。

### 2.1 Codex 变更报告契约

每次 Codex 修改代码或运行时配置时，最终报告必须回答以下问题。用户不需要审查
实现细节，但要能审查这次变更是否仍服务 `docs/architecture-design.md` 的目标。

变更前检查：

1. 本次改动对应哪个架构目标：完成率、避免假完成、错误恢复、重规划、可观测、
   benchmark 或减少用户参与
2. 是否触碰 `VerifierAgent`、`CompletionGate`、`HumanAssistanceGate`、
   `WorldState`、`ToolResult`、`RecoveryPolicy`、预算或安全阻断
3. 是否可能增加虚假成功、隐藏失败、增加用户打扰或削弱安全确认
4. 是否只是为了兼容旧结构而牺牲完成率
5. 需要跑哪些测试、benchmark、trace replay 或人工小样本检查

变更后报告：

1. 改了什么，涉及哪些关键文件
2. 为什么这会提升完成率、降低假完成、增强恢复或减少用户参与
3. 实际跑了哪些测试、benchmark 或 trace 检查
4. 还没验证的风险是什么
5. 是否需要用户做方向判断，还是可以继续由 Codex 推进

高影响变更必须显式升级给用户，包括完成判定、用户协助、安全阻断、预算策略、
基线晋升、失败分类体系和验收阈值变化。

## 3. 护栏核心

第一版不建设独立通用平台，也不要求跨项目 adapter。护栏分为两层：轻量可沉淀
协议层，以及当前唯一实现目标 Lynx 项目适配层。

### 3.1 可沉淀协议层

协议层可以跨项目复用，但第一阶段只以文档、schema、清单和报告格式存在。只有在
Lynx 中被验证有效的内容，才进入协议层。

核心概念：

1. `Scenario`：任务定义与成功判定器。
2. `Observation`：agent 在某一步能看到的内容。
3. `RenderedInput`：实际发送给模型的 prompt、packet、工具列表或输入。
4. `AgentDecision`：模型或策略的输出。
5. `ToolResult`：外部动作的结构化结果。
6. `RuntimeState`：观察和动作更新后的任务状态。
7. `OracleResult`：确定性或验证器的成功判定。
8. `Trace`：单次运行的只追加事件流。
9. `RunSummary`：单次运行的指标和终止状态。
10. `ExperimentVariant`：prompt、packet、模型、策略或观察的变体。
11. `FailureCategory`：标准化的失败或回归原因。
12. `AcceptanceGate`：变更是否可保留、拒绝或升级的规则。
13. `ReviewReport`：面向用户的方向级报告。

沉淀规则：

1. 不为了未来复用牺牲当前 Lynx 完成率和落地速度
2. 不在第二个项目出现前抽象多 adapter 运行平台
3. 优先沉淀 schema、报告模板、验收门规则、失败分类和审查清单
4. 只有重复出现、已经通过 Lynx 证据验证的实践，才进入协议层

### 3.2 Lynx 项目适配层

当前只实现 Lynx 适配层：

1. `docs/architecture-design.md`：架构真相源和开发方向约束
2. Android 截图作为默认模型输入；UI tree、OCR、visible texts 和 typed candidates
   默认作为内部观察、trace、oracle 和消融变量
3. `WorldState`、`ExecutionPacket`、`ToolResult` 和 primitive tools
4. `VerifierAgent + CompletionGate` 完成判定闭环
5. `HumanAssistanceGate`、安全阻断、预算和恢复策略
6. `tools/run_device_benchmark.sh` 和少量真实手机任务场景

未来其他项目可以实现自己的适配层，例如 code 项目使用测试结果、diff 和 shell
trace，Web 项目使用 DOM、截图和浏览器动作。但这属于后续迁移，不是当前 MVP。

## 4. 产物布局

每次运行应产生一个自包含的产物目录。

```text
artifacts/agent-runs/<run_id>/
  meta.json
  trace.jsonl
  summary.json
  screenshots/
  observations/
  rendered-inputs/
  decisions/
  verifier/
  tool-results/
```

`trace.jsonl` 是规范的只追加事件流。截图、UI 树、完整 rendered packet 等较大
负载可存储为独立文件，在事件中通过路径引用。

### 4.1 规范 Trace 事件类型

每条 trace 条目包含 `event_type`、`timestamp`、`step_index` 和
`world_state_version`。以下事件类型是回放和回归分析的必要条件：

1. `run_start`：场景 id、适配器、模型、prompt 版本、packet 变体。
2. `observation`：截图引用、UI 文本、OCR 文本、可见文本、已分类候选项、页面
   签名、当前 app。
3. `rendered_input`：发送给模型的完整执行 packet（或引用路径）。
4. `agent_decision`：模型原始输出、解析后动作、候选项 id、置信度。
5. `tool_call`：工具名、参数、目标候选项 id、目标标签。
6. `tool_result`：结构化 `ToolResult` 字段（status、changed、before/after
   signature、error type）。
7. `finish_proposed`：executor 发出 finished；尚未成功。
8. `verifier_invoked`：发送给验证器的输入（目标、条件、证据）。
9. `verifier_result`：complete、confidence、evidence、missing、next hint。
10. `completion_gate_result`：accepted、拒绝原因、剩余拒绝次数。
11. `recovery_triggered`：信号类型、恢复级别、提示、剩余预算。
12. `replan_triggered`：原因、旧策略摘要、新策略摘要。
13. `human_assistance_requested`：请求类型、提示语、阻塞描述。
14. `human_assistance_resolved`：用户动作、耗时、结果。
15. `run_finish`：终止状态、失败分类、全部汇总指标。

Lynx 适配器应通过挂接现有 `FlowTraceLogger` 的输出来生成这些事件。
`FlowTraceLogger` 已经在输出结构化的阶段日志行；适配器将其转换为上述规范事件
类型。

### 4.2 运行时不变量检查

trace 记录器必须在每次事件写入时验证运行时契约。不变量违规记录为
`invariant_violation` 事件并标记该运行。

必需的不变量：

1. 相邻两次 `observation` 事件之间最多只能有一个 `tool_call` 事件
   （executor 单动作契约）。
2. 每个 `tool_result` 必须有结构化 status 字段，不能仅为字符串消息。
3. `finish_proposed` 之后必须紧跟 `verifier_invoked`，然后才能出现
   `completion_gate_result`。
4. 不能出现 `terminal_status=success` 的 `run_finish` 而之前没有
   `accepted=true` 的 `completion_gate_result`。

必需的 `summary.json` 字段：

```json
{
  "run_id": "string",
  "scenario_id": "string",
  "suite_id": "string",
  "agent_adapter": "lynx",
  "runtime_version": "string",
  "model": "string",
  "prompt_version": "string",
  "packet_variant": "baseline",
  "terminal_status": "success|false_success|blocked|exhausted|safety_blocked|crashed",
  "failure_category": "string|null",
  "completion_rate_eligible": true,
  "invariant_violations": 0,
  "step_count": 0,
  "replan_count": 0,
  "finish_rejection_count": 0,
  "tool_noop_count": 0,
  "repeated_action_count": 0,
  "human_assistance_request_count": 0,
  "user_confirmation_count": 0,
  "user_text_count": 0,
  "user_action_count": 0,
  "user_minutes_estimate": 0.0,
  "context_variant": "vision_minimal",
  "raw_xml_in_prompt": false,
  "full_ui_tree_in_prompt": false,
  "candidate_list_in_prompt_count": 0,
  "model_visible_candidate_handle_count": 0,
  "raw_coordinate_click_count": 0,
  "guardrail_integrity_status": "passed|failed|unknown",
  "input_tokens": 0,
  "output_tokens": 0,
  "cost_breakdown": {
    "planner_tokens": 0,
    "executor_input_tokens": 0,
    "executor_output_tokens": 0,
    "verifier_tokens": 0,
    "total_input_tokens": 0,
    "total_output_tokens": 0,
    "estimated_usd": 0.0
  },
  "started_at": "string",
  "finished_at": "string"
}
```

### 4.3 护栏自检协议

验证护栏自身必须先通过校准，才能作为 Codex 自主迭代的决策依据。

护栏价值链：

```text
trace 记录 -> 判定器评判 -> 指标计算 -> 验收门决策 -> Codex 假设方向
```

任何一环失准，护栏输出都不可信。自检失败时，相关套件结果不得用于基线晋升，
自主实验循环必须暂停，直到测量问题被修复并重新通过自检。

硬规则：

1. 未通过自检的工具输出只能作为调试参考，不能用于保留变更、晋升基线或判断方向。
2. 如果工具自身错误导致误判，优先修工具和校准样本，不允许 Codex 继续围绕错误指标优化。
3. 平台验收可以参考 runtime `VerifierAgent`，但不能只依赖被测 runtime 的 verifier
   自证自己。
4. 明确的确定性失败不能被 LLM verifier 覆盖；只有证据不足或无法判定时才能 fallback。

自检层级：

1. **Trace 完整性验证**：使用人工确认过事件序列的 golden runs 校验
   `trace.jsonl`。事件数量、顺序和关键引用必须与人工确认一致，trace 丢失率
   必须为 0。
2. **判定器校准验证**：使用人工标注的 labeled examples 校验确定性判定器和
   `VerifierAgent`。确定性判定器在适用用例上必须 100% 准确；不适用时应返回
   unknown，而不是硬判。Verifier 可有噪声，但必须达到配置阈值。平台验收可以
   参考 runtime `VerifierAgent`，但不能只依赖被测 runtime 的 verifier 自证自己；
   优先使用 deterministic oracle、人工 labeled examples 或独立校准样本。
3. **验收门校准验证**：使用模拟的 baseline/candidate `summary.json` 校验门
   决策。已知改进必须通过，已知回归必须拒绝，以完成率换虚假成功必须拒绝，
   分类间权衡不明确时必须升级。
4. **基线健康检查**：定期从零重跑完整套件，与当前基线比较。差异超过阈值时，
   标记基线可疑，冻结自主迭代并升级给用户。

最小校准数据：

1. 至少 3 个 golden runs，用于 trace 完整性。
2. 至少 10 个 labeled examples，用于判定器校准，其中包含明确成功、明确失败和
   边界用例。
3. 至少 4 个模拟门决策用例：改进、回归、完成率/虚假成功权衡、分类间冲突。
4. 每 5 次基线晋升后，或按固定周期，运行一次基线健康检查。

### 4.4 护栏工具准确性

协助 Codex 工作的工具本身也是风险源。它们的准确性必须被当作产品方向的一部分
管理，否则系统会从"Codex 凭感觉偏航"变成"Codex 被错误指标带偏"。

第一版必须覆盖的准确性检查：

1. **Trace 记录准确性**：事件不能漏记、乱序或截断关键字段；`trace.jsonl` 必须能
   通过 golden runs 对齐人工确认的事件数量、顺序和关键引用。
2. **Oracle / Verifier 准确性**：deterministic oracle 在适用场景必须 100% 准确；
   不确定时返回 `unknown`。LLM verifier 是有噪声判定器，必须用 labeled examples
   校准，不能作为唯一真相源。
3. **Metrics 准确性**：完成率、虚假成功率、恢复率、用户参与率和成本的分母必须
   固定且可解释；异常运行不能被随意排除来抬高指标。
4. **Acceptance Gate 准确性**：gate 必须有单元测试和模拟样例。已知改进必须通过，
   已知回归必须拒绝，用虚假成功换完成率必须拒绝，模糊权衡必须升级。
5. **Benchmark Runner 准确性**：真机 benchmark 必须记录起始状态、设备状态、目标 app、
   账号/权限状态和运行环境。无法确认起始状态时，该运行只能作为参考。
6. **Report 准确性**：面向用户的报告必须能链接回 trace、summary、失败样本和门决策；
   不能只给"Codex 认为变好了"的摘要。

准确性检查失败时，Codex 可以继续修复护栏工具本身，但不能用失败工具的结果保留
业务变更或建议方向晋升。

## 5. 场景注册表

场景应为结构化文件，而不仅是自然语言示例。

```yaml
id: settings_wifi_001
category: setting_toggle
instruction: 打开设置并进入 WLAN 页面
start_state:
  app: launcher
success_oracle:
  type: composite
  deterministic_checks:
    - type: app_foreground
      app: com.android.settings
    - type: text_visible
      pattern: "WLAN|Wi-Fi"
  verifier_fallback:
    required_observable_evidence:
      - 当前页面显示 WLAN 或 Wi-Fi 相关设置
  verifier_prompt_override: null
risk_tags:
  - navigation
  - settings
noise_tags:
  - popup
  - language
  - device_size
budgets:
  max_steps: 60
  max_replans: 5
  max_finish_rejections: 4
  cost_budget_usd: 0.50
```

### 5.1 判定器类型

三种判定器类型，从最廉价到最具表达力：

1. `deterministic`：完全通过结构化后置条件判定成功（前台 app、文本可见、元素
   存在、开关状态）。不调用 LLM。由于判定噪声为零，是自主迭代的首选。
2. `verifier`：由基于 LLM 的 `VerifierAgent` 判定成功。存在误接受和误拒绝
   噪声。
3. `composite`：先运行确定性检查。确定性检查结果必须区分 `pass`、`fail` 和
   `unknown`。全部 `pass` 时直接接受成功，不调用验证器；出现明确 `fail` 时直接
   拒绝成功，不能被 LLM 覆盖；只有结果为 `unknown` 或证据不足时，才调用验证器
   作为兜底。这在保持覆盖率的同时降低判定噪声，并避免 LLM 覆盖确定性失败。

确定性检查类型：

1. `app_foreground`：当前前台 app 与 `app` 匹配。
2. `text_visible`：可见文本、OCR 文本或 UI 文本匹配 `pattern`。
3. `element_present`：存在匹配 `text` 或 `id` 的已分类候选项。
4. `element_absent`：不存在匹配 `text` 或 `id` 的已分类候选项。
5. `toggle_state`：开关元素处于预期的 `checked` 状态。

当某个场景同时有确定性和验证器判定结果时，护栏应追踪
`oracle_disagreement_rate` 以检测验证器漂移。

初始 Lynx 场景分类：

1. 应用启动与导航
2. 搜索
3. 设置开关
4. 表单填写
5. 消息编写与发送
6. 滚动与选择
7. 弹窗打断
8. 错误转向恢复
9. 完成验证边界用例
10. 外部阻塞与人工协助

## 6. 指标

主要指标：

1. 经验证的完成率
2. 虚假成功率
3. 恢复成功率

次要指标：

1. 每个成功任务的平均步数
2. 每个成功任务的平均重规划次数
3. 完成拒绝次数
4. 工具空操作率
5. 重复动作率
6. 候选项 id 点击率
7. 原始坐标点击率
8. 阻塞率
9. 预算耗尽率
10. 每个成功任务的成本
11. 每个成功任务的延迟
12. 人工协助请求率
13. 用户确认次数
14. 用户文本提供次数
15. 用户实际操作次数
16. 每个成功任务的用户参与分钟数
17. 上下文变体
18. raw XML / full UI tree 是否进入 prompt
19. 候选项列表进入 prompt 的数量
20. 每步输入 token 与空响应率

指标必须按总体和分类两个维度报告。单一汇总完成率不够，因为它可能隐藏特定任务
类型的回归。

## 7. 失败分类体系

每次失败或可疑的运行应有一个主要失败分类和可选的次要标签。

初始分类体系：

1. `planner_wrong_app`
2. `planner_bad_success_criteria`
3. `executor_wrong_action`
4. `candidate_noise`
5. `ocr_noise`
6. `ui_text_noise`
7. `context_overload`
8. `vision_grounding_error`
9. `coordinate_parse_error`
10. `observation_missing`
11. `tool_noop`
12. `tool_error`
13. `repeated_action_loop`
14. `replan_missing`
15. `verifier_false_accept`
16. `verifier_false_reject`
17. `completion_gate_too_loose`
18. `completion_gate_too_strict`
19. `external_blocker`
20. `safety_block`
21. `budget_exhausted`
22. `runtime_crash`

第一版可使用确定性规则分配标签，并允许人工修正。修正后的标签成为后续自动分类
的训练数据。

## 8. 实验类型

护栏应支持四类实验。

### 8.1 回放回归

回放使用历史观察和运行时状态，不操作手机。成本低，可高频运行。

适用于：

1. prompt 变更
2. packet 字段顺序
3. 解析器变更
4. 验证器 prompt 变更
5. 可从历史状态模拟的恢复策略变更

回放契约：

1. **输入**：trace 产物提供每个决策步骤的观察和 world state 快照。
2. **重跑内容**：使用新的 prompt、packet 变体或模型重新运行 agent 决策函数
   （executor 或验证器）。world state 和观察保持与原始 trace 一致。
3. **比较**：将每个重新计算的决策与原始决策进行 diff。变化的决策标记为
   `changed_action`、`changed_finish` 或 `changed_verifier_outcome`。
4. **范围**：默认回放完整 episode。支持单步回放用于定向调查。
5. **非确定性处理**：使用非确定性模型时，每个决策步骤重跑 K 次（默认 K=3）。
   护栏报告多数投票决策、一致率和分歧率。只有当多数投票结果与原始不同时，才
   标记为 `regression`。

### 8.2 观察消融

观察消融测试某个观察通道是帮助还是损害了任务完成。

初始 Lynx 变体：

1. `vision_minimal`：截图、目标、工具、最近动作结果、必要恢复/验证提示
2. `vision_recent_result`：在 `vision_minimal` 上强化最近 `ToolResult`
3. `vision_short_text`：增加少量可见文本摘要
4. `vision_compact_candidates`：增加少量相关候选项摘要，但不暴露候选动作句柄
5. `candidate_handle_required_ablation`：强制候选句柄动作，只用于否定该方向，不得晋升默认路径
6. `raw_xml_added`：增加 raw XML，只用于消融，不得默认启用
7. `compressed_tree_added`：增加压缩 UI tree，只用于消融，不得默认启用
8. `minimal_low_cost`：进一步压缩上下文和工具描述

示例问题：

```text
原始 XML 是否提升了完成率，还是增加了误点击、重复动作、
选择屏幕外元素和 token 成本？
```

默认假设不是"结构越多越好"，而是"模型能直接从截图理解页面"。任何结构化输入都必须
通过对照实验证明它是帮助而不是噪音。

### 8.3 设备基准测试

设备基准测试在模拟器或真机上运行真实 Android 任务。更慢、噪声更大，但它是
Lynx 完成行为的最终仲裁者。

适用于：

1. 真实工具执行
2. 真实 UI 时序
3. 弹窗处理
4. 键盘和输入法问题
5. 截图/OCR 噪声
6. 账号和应用状态变化

当前 Lynx 第一版允许 debug 包通过 `tools/run_device_benchmark.sh --auto-run` 自动提交
单条 instruction。该入口只服务本地设备验证，目的是减少手动坐标输入造成的噪声；它不
代表建设独立通用平台，也不能绕过 Verifier / CompletionGate。

### 8.4 影子生产评估

生产或 dogfood 的 trace 应被采样进入离线审查。护栏应在无人观看实时任务时检测
可能的虚假成功、循环和预算浪费。

## 9. 验收门

Codex 可在验收门内自主迭代。变更不应仅因"看起来合理"而被保留。

完成率优先阶段的默认门：

1. 经验证的完成率不得在固定回归套件上回归
2. 虚假成功率不得上升
3. 安全阻断行为不得削弱
4. 恢复成功率不得在错误转向恢复、弹窗打断和循环敏感场景上回归
5. 循环敏感场景的重复动作率不得上升
6. 用户参与成本不得在同类场景显著上升，除非完成率收益明确且升级给用户确认
7. 基础建设阶段成本可以上升，但 `estimated_usd` 必须在每套件
   `cost_budget_usd` 范围内
8. 任何以完成率换取虚假成功的改进必须被拒绝
9. 任何模糊结果必须升级处理，附带代表性 trace
10. 护栏工具自检失败时，相关结果只能作为参考，不能用于保留变更或晋升基线
11. `invariant_violations > 0` 的候选运行默认整轮 gate fail，不能只从完成率分母中
   排除；只有确认违规来自 trace 记录器 bug，且修复后重新通过自检，才可重新评估
12. 任何依赖业务 app 硬编码、页面文案特判、固定流程、固定坐标或 benchmark 专用
   prompt 的候选改进默认 gate fail；业务知识只能保留在 scenario、oracle、fixture
   评估侧
13. 任何把页面专用 intent、子页面直达工具或本地自动启动 bootstrap 暴露给 runtime
   Executor，并把由此产生的成功计入核心完成率的候选改进默认 gate fail；这类能力只能
   作为 benchmark prep、oracle 或基础链路 smoke 标注
14. 任何把 raw XML、完整 UI tree、长候选列表或候选句柄动作提升为默认上下文
   或默认执行路径的候选，默认 gate fail；即使作为实验，也必须先通过观察消融证明
   完成率或 first-attempt 成功率提升，且 false success、重复动作、timeout、用户
   参与和 token 成本不恶化

完成率足够高之后，增加更严格的成本门：

1. 每个成功任务的成本必须下降或持平
2. 平均步数应下降或持平
3. 只有在虚假成功防护保持稳定时，才可以减少验证器调用

### 9.1 基线管理

基线是变更比较的参考结果集。

1. **创建**：在当前代码上运行完整回归套件。产生的 `summary.json` 文件汇集为
   `baselines/<suite_id>/baseline.json`。
2. **存储**：基线提交到仓库的 `baselines/` 目录下。每个基线记录每场景的
   `terminal_status`、`step_count`、`failure_category`、关键指标和 git commit
   hash。
3. **晋升**：变更通过所有验收门后，候选结果成为新基线。晋升需要显式提交命令
   或人工批准（可按套件配置）。
4. **历史**：旧基线保存在
   `baselines/<suite_id>/history/<timestamp>_<commit>.json`，用于趋势分析和
   回滚。
5. **恢复**：如果已晋升的基线后来被发现有害，先从历史中恢复之前的基线记录，
   并生成候选代码恢复方案。代码层面的 git 回退必须由用户明确要求后再执行。

## 10. 人类参与模型

护栏应最小化人类注意力，同时保留人类对方向的控制。

用户负责：

1. 产品目标和任务优先级
2. 重要场景组的成功定义
3. 虚假成功容忍度
4. 安全和不可逆操作规则
5. 验收阈值
6. 对新失败模式的小样本审查

Codex 负责：

1. 实现细节
2. trace 审查
3. 假设生成
4. 实验执行
5. 失败预分类
6. 报告生成
7. 添加回归用例
8. 提议下一步工作

升级触发条件：

1. 完成率相对当前基线变化超过 `±5%`
2. 出现新的失败分类
3. 同一失败分类或同一实验假设连续 3 次被拒绝
4. 恢复成功率、虚假成功率或安全阻断行为发生明显退化

非升级情况下：

1. Codex 继续按既定验收门自动迭代
2. 用户仅接收周汇总，不需要逐次介入
3. 周汇总包含实验数、通过数、拒绝数、升级数、主要回归和下一步建议

面向用户的报告应简短且方向性：

```text
实验: obs_ablation_vision_minimal_v1
假设: 以截图 + 目标 + 最近 ToolResult 为主，减少候选项噪声后，模型更少偏移。
结果:
  完成率: 62% -> 68%
  虚假成功: 4% -> 3%
  重复动作: 13% -> 7%
  timeout: 9% -> 5%
  成本: -11%
决策: 保留
需要用户审查: 无
下一步: 在密集列表场景上对比 vision_short_text 与 vision_compact_candidates
```

## 11. MVP 实施顺序

### 当前执行切片：Phase 2 + Phase 3

当前不继续扩大独立平台范围，而是进入可落地的能力型验证与通用执行能力优化。

执行目标：

1. `completion_smoke` 和 `settings_noise` 继续作为回归门，只证明基础闭环没坏
2. 新增能力型 benchmark suite，按通用能力分类，而不是按某个业务 app 分类
3. 第一轮优化 `Model Understanding + Action Grounding`：让 agent 在少量上下文下更
   准确理解截图、选择坐标动作、利用最近工具结果恢复，而不是强制走 candidate-first
4. 同步修补模型接口可信度：模型响应解析、空响应分类、tool-call envelope 兼容、
   token/trace 记录必须足够可靠，否则完成率数据不可信
5. 所有收益必须来自 runtime 的通用能力提升，不能来自 Settings、WLAN、蓝牙、电池
   或任何页面文案的 runtime 特判

能力型 suite 第一版：

1. `ability_input_grounding.tsv`：搜索框发现、聚焦、输入和输入失败恢复
2. `ability_list_navigation.tsv`：密集列表中的视觉定位、滚动、相邻行干扰和点错恢复
3. `ability_wrong_page_recovery.tsv`：从错误相邻页面返回、换路径、继续完成原目标
4. `ability_observation_noise.tsv`：对比 OCR、UI 文本、候选项、XML 是否帮助或干扰模型
5. `ability_visual_coordinate.tsv`：只靠截图、目标和坐标工具完成基础视觉点击任务

这些 suite 可以使用稳定系统页面作为 fixture，但它们评估的是通用能力：

1. 模型是否能基于截图和目标选择合理坐标动作
2. 最近 `ToolResult`、verifier missing evidence、recovery hint 是否让模型更快恢复
3. OCR、UI 文本、候选项和 XML 对不同任务类别到底是帮助还是噪音
4. 是否在输入前确认可编辑目标或可搜索状态
5. 是否在点错页面后利用 `press_back`、重新观察或重规划恢复
6. 是否避免重复无效点击和重复无效输入
7. 模型接口异常是否被准确归因：provider envelope、content array、native tool_calls、
   空 content 不能被混同为 agent 智能失败

验收门：

1. `./gradlew test --continue compileReleaseKotlin` 必须通过
2. `completion_smoke` 最终成功必须保持 `status=success && trace_integrity=pass`
3. 本轮目标 suite 的最终成功率或 first-attempt 成功率必须提升，或至少暴露出更准确
   的失败分类
4. false success、`completion_gate_rejected` 后重复 finished、用户参与次数不能恶化
5. runtime 代码不能新增 app/page/文案/固定坐标特判
6. 默认上下文不能新增 raw XML、完整 UI tree、长候选列表或候选句柄动作；相关内容
   只能作为离线消融或 trace/oracle 信号，观察消融证明收益后也必须另设显式 variant

Phase 3 第一轮通用改动方向：

1. `click(x,y)` 是主路径。只拦截无参数、越界、不可解析或安全风险动作；不得因为页面
   存在候选项就拦截合法坐标点击
2. `click {}`、缺少 point/x/y 的调用必须在执行前转成结构化失败，进入 `WorldState`
   作为下一轮 recovery signal，而不是静默失败
3. 候选项只能保留为内部 trace、replay、oracle、调试和离线消融信号，不作为模型
   动作接口
4. ExecutionPacket 默认使用 `vision_minimal`：目标、截图、可用工具、最近动作结果、
   verifier missing evidence、recovery hint、当前 app；其他观察通道通过 variant 测试
5. raw XML、完整 UI tree、长 OCR/UI text、长候选列表默认只落 trace，不进入 prompt
6. 如果某个任务类别确实需要结构化文本，只能加入短、相关、可消融的摘要，并记录
   `context_variant`
7. 当 Planner 因模型空响应无法给出 app query 时，fallback 只能保留用户原始目标作为
   通用 app 查询，并由已安装应用解析工具处理；不能写中文 app 名到包名的业务映射
8. 当目标 app query 已存在且当前不在目标 app 时，Executor 也必须让模型选择
   `resolve_installed_app` / `open_app`；本地自动 bootstrap 只能作为 benchmark prep，
   不能进入核心 runtime 路径
9. 对 OpenAI-compatible / OTHER provider 的响应解析必须保持通用：支持纯文本 JSON、
   content string、content array、native `tool_calls` / `function_call`；如果 provider
   返回空 content 但带有原生 tool call，必须保留 envelope 交给 runtime 解析
10. 如果响应体无法提取可执行内容，要记录 `api_empty_content` 这类结构化 trace，
   用于区分“模型确实空响应”和“客户端解析不兼容”
11. vision 连续空响应时，允许使用同一屏幕的短结构化文本上下文做一次 text-only fallback；
   这是模型接口恢复能力，不是页面业务捷径
12. 候选摘要只能作为显式观察消融 variant，按目标、原始指令、成功条件做通用相关性
    排序；排序只能基于文本重合和结构化候选信息，不能写 app/page/业务文案特判；
    未通过消融前不得默认启用，且不得暴露候选动作句柄

完成后报告必须给出：

1. 对完成率或 first-attempt 成功率的影响
2. 是否增加假完成风险
3. 是否增加用户参与
4. 跑了哪些 suite
5. 是否通过 `status=success && trace_integrity=pass`

### 阶段 1：Trace 基础

交付物：

1. `TraceRecorder` 接口，实现 §4.1 规范事件类型
2. 运行时不变量检查器，实现 §4.2 不变量
3. 运行产物目录写入器
4. trace 事件 schema
5. 运行汇总写入器，包含 `cost_breakdown` 和 `invariant_violations`
6. 护栏工具准确性自检命令，至少覆盖 trace 完整性、summary 指标计算和 gate 决策
7. Lynx 适配器，将现有 `FlowTraceLogger` 输出转换为规范 trace 事件，同时在
   orchestrator、executor packet 生成、tool result、verifier 和 completion
   gate 中增加新的 hook

退出标准：

1. 每次任务运行都有可回放的产物目录
2. 终止状态和核心指标被一致写入
3. 记录不需要改变运行时行为
4. 不变量违规被检测并记录
5. 至少 3 个 golden runs 的 trace 事件序列与人工确认完全一致
6. trace 丢失率为 0
7. 自检失败的运行不能用于保留变更或建议基线晋升

### 阶段 2：场景注册表与批量运行器

交付物：

1. `benchmarks/scenarios/*.yaml`，使用 §5 schema 和判定器类型
2. `benchmarks/suites/*.yaml`，包含 `cost_budget_usd`
3. 场景加载器，附带确定性判定器评估器
4. 批量运行器，封装现有 `tools/run_device_benchmark.sh`。现有脚本已能捕获
   logcat、解析 LynxFlow trace 并计算指标（重复动作、验证器事件、重规划事件）。
   批量运行器复用该逻辑，增加场景迭代、判定器评估和汇总报告功能。
5. 汇总 Markdown/CSV 报告
6. 初始基线创建并存储到 `baselines/` 下

退出标准：

1. 冒烟套件能运行多个场景
2. 报告包含每场景和每分类的完成率、虚假成功、步数、空操作、重复动作和成本
3. 确定性判定器在重叠场景上产生与验证器一致的结果
4. 冒烟套件已有基线
5. 至少 10 个 labeled examples 完成判定器校准
6. 确定性判定器在适用的 labeled examples 上准确率为 100%
7. Verifier 在 labeled examples 上准确率不低于 80%
8. 确定性判定器和 Verifier 在重叠用例上的 disagreement 不超过 1 个

### 阶段 3：回放运行器

交付物：

1. 从历史 trace 产物回放
2. 模型/prompt/packet 变体覆盖
3. 与基线的决策比较
4. 包含变化决策和可能回归的回放报告

退出标准：

1. prompt 和 packet 变更可以在不使用真机的情况下评估
2. 代表性的变化决策可从产物中检查

### 阶段 4：观察消融工具

交付物：

1. 围绕 `ExecutionPacketBuilder` 的 packet 变体层
2. 基线和消融变体
3. 回放消融报告
4. 小规模真机确认套件

退出标准：

1. 团队能回答 XML、OCR、UI 文本、候选动作和已分类候选项对每个任务类别是帮助
   还是损害
2. 默认 packet 只包含已经证明有收益、且不会明显增加噪音的上下文字段
3. 若 `vision_minimal` 优于结构化变体，必须保持视觉优先默认路径；不能为了架构完整
   强行引入 XML 或 candidate-first

### 阶段 5：失败审查队列

交付物：

1. 确定性失败预分类器
2. 人工修正的审查文件格式
3. 回归集晋升工作流
4. 头部失败分类报告

退出标准：

1. 每个主要失败要么归入已知分类，要么创建新分类
2. 修正后的样本反馈到后续的回放和基准测试套件

### 阶段 6：自主实验循环

交付物：

1. 实验清单格式
2. 基线比较命令
3. 验收门评估器
4. 自动生成的实验报告
5. 保留/拒绝/升级 决策输出
6. 候选回退方案或恢复说明（只有在用户明确要求时才执行破坏性 git 回退）

退出标准：

1. Codex 能运行限定范围的实验并产出基于证据的推荐
2. 用户主要审查方向和例外情况
3. 4 个模拟门决策用例全部产生预期结果
4. 验收门评估器有对应单元测试
5. 基线健康检查机制已实现：每 5 次基线晋升后重跑完整套件，差异超过 10% 时
   冻结自主迭代并升级给用户

假设来源链路：

1. 失败报告先按分类聚合，挑出新出现或高频失败模式
2. Codex 读取代表性 trace，定位发生偏移的具体步骤
3. 从 trace 中提取根因候选，例如错误观察、错误 packet、错误动作、verifier 误判
   或恢复策略不足
4. Codex 将根因候选转写为下一轮可验证假设
5. 若同一失败分类或同一假设连续 3 次被验收门拒绝，则升级给用户审查，附带
   代表性 trace、对比结果和权衡摘要

阶段 6 控制流：

```text
1. Codex 提出变更：
   - 代码 diff 或配置变更
   - 假设（应改进什么、为什么）
   - 实验清单（哪个套件、哪个变体、运行几次）

2. 护栏执行：
   - 使用候选变更运行 run_suite
   - 将结果与当前基线比较
   - 评估验收门

3. 门决策：
   - 保留：
     保留为候选变更
     生成是否建议提交、是否建议晋升基线的简短报告
     只有在用户明确要求、或仓库配置允许时，才执行提交或基线晋升
   - 拒绝：
     丢弃候选方案或提出最小回退补丁
     记录拒绝原因和代表性 trace
     Codex 读取拒绝信息并提出不同方案
   - 升级：
     创建审查项，附带代表性 trace 和权衡摘要
     阻塞直到用户批准或拒绝
     如批准则继续；如拒绝则丢弃候选方案或回退补丁

4. Codex 读取门输出并决定下一个假设。
   循环持续进行，直到 Codex 没有更多假设或用户设定新方向。
```

每次迭代的触发方式：

1. Codex 或人工手动命令
2. git push 到实验分支（CI 集成）
3. 定时 cron 用于夜间批量实验

## 12. 建议命令

第一版应优先复用现有仓库命令和少量脚本，无需构建完整服务。

当前仓库内的基础命令：

```bash
./gradlew test --continue

tools/run_device_benchmark.sh \
  --scenario-id smoke \
  --instruction "open settings" \
  --launch
```

以下命令属于后续可选的轻量封装，不是第一阶段必须建设的独立 `agentlab` 平台：

```bash
tools/agentlab/run_suite.sh \
  --suite benchmarks/suites/smoke.yaml \
  --adapter lynx \
  --packet-variant baseline

tools/agentlab/replay.sh \
  --runs artifacts/agent-runs \
  --packet-variant vision_minimal \
  --prompt-version executor_v14

tools/agentlab/compare.sh \
  --baseline artifacts/experiments/baseline \
  --candidate artifacts/experiments/obs_ablation_vision_compact_candidates_v1

tools/agentlab/report.sh \
  --experiment artifacts/experiments/obs_ablation_vision_compact_candidates_v1
```

## 13. 外部工具

Codex 开发护栏应以本地 JSONL 产物、测试输出和 benchmark 报告作为事实来源。
外部工具可作为后续观测或展示适配层，但不是第一阶段依赖。

值得后续评估的开源工具：

1. Langfuse 或 LangWatch：tracing UI、prompt 版本和在线评估
2. Phoenix：基于 OpenTelemetry 的本地可观测性
3. promptfoo 或 DeepEval：prompt 级别回归测试
4. agentevals：OpenTelemetry trace 评分
5. AndroidWorld、OSWorld、BrowserGym 和 AgentLab：基准测试设计参考

观察消融和 Lynx 完成判定器应保持在本仓库内，因为它们依赖 Lynx 特有的
`WorldState`、`ToolResult`、`ExecutionPacket`、`VerifierAgent` 和
`CompletionGate` 语义。

## 14. 非目标

本护栏初期不应优化：

1. 精美的仪表盘
2. 大规模 ML 训练流水线
3. 技能挖掘
4. 替代 Lynx 运行时契约
5. 完全无监督的产品方向
6. 为提高标题完成率而隐藏虚假成功
7. 建设独立通用 AgentLab 平台
8. 默认自动提交代码或自动晋升基线
9. 默认信任未校准的 trace、判定器、指标、验收门或报告

第一个有用的版本是可靠的产物、回放、基准测试和报告循环，让 Codex 开发时有证据
护栏。UI 美化和通用平台化可以等。

## 15. 审查清单

回头验证本文档时，优先检查以下问题：

1. **目标是否正确**：所有指标和验收门是否仍以真实完成率、虚假成功率和恢复成功率
   为中心。
2. **护栏是否可信**：§4.3 和 §4.4 的工具准确性、trace 完整性、判定器校准、
   验收门校准和基线健康检查是否足以发现护栏自身错误。
3. **Codex 是否能自主推进**：§11 阶段 6 是否清楚定义了失败报告如何转成假设、
   实验、门决策和下一轮迭代。
4. **用户是否少参与但不失控**：§10 的升级触发条件和周汇总是否能减少打扰，同时
   在完成率大幅变化、新失败分类、连续拒绝或安全退化时及时拉回用户。
5. **Codex 变更是否可审**：§2.1 是否让用户不用看实现细节，也能判断每次改动
   是否仍服务架构目标、是否有假完成风险、是否需要方向判断。
6. **范围是否收敛**：§3 是否明确第一版服务 Lynx 与 Codex 开发过程，而不是引导
   去建设独立通用平台。
7. **复用是否轻量**：§3.1 是否把复用限制为协议、schema、报告、验收规则和失败
   分类，而不是提前建设多 adapter 平台。
8. **是否可落地**：§11 的阶段顺序是否从 trace、场景、回放、消融、失败审查到
   自主实验逐步推进，没有要求一开始建设完整平台。
9. **是否防止指标游戏化**：是否明确禁止用虚假成功换完成率，是否让
   `invariant_violations` 默认导致候选 gate fail，是否有基线腐化检测。
10. **工具是否准确**：协助 Codex 工作的 trace、oracle、metrics、gate、benchmark
   和 report 是否先通过自检；未通过自检的结果是否只作为参考。
11. **上下文是否真的帮助模型**：新增 XML、UI tree、OCR、候选项或候选句柄约束前，
   是否通过消融证明完成率提升，且没有增加假完成、重复动作、timeout、用户参与或
   token 成本。
12. **主路径是否仍是通用 agent 能力**：默认路径是否仍然是截图视觉理解、坐标动作、
   结构化工具结果和恢复闭环，而不是业务脚本、页面特判或复杂结构化自动化。

如果以上任一项不成立，应先修本文档或护栏自检协议，再允许 Codex 进入自主实验
循环。
