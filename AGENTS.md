# AGENTS.md — Lynx Completion-First Guardrails

## Source Of Truth

- `docs/architecture-design.md` 是唯一架构真相。
- 旧 runtime 设计已经废弃。
- 本期目标是任务完成率，不是兼容旧架构、保持最少 agent、或提前沉淀可复用技能。
- 本工程是通用 Android agent，不是某个业务 app 的自动化脚本集合；任何提升都必须提升
  agent 能力本身，不能靠业务相关硬编码、页面补丁或固定流程刷完成率。
- 本期能力提升方向是提升模型对手机屏幕和任务上下文的理解能力。默认以截图视觉理解
  和坐标动作为主路径；XML、UI tree 和候选项列表只能作为 trace、调试、oracle
  或离线消融信号，不能成为模型可见动作接口或默认压过视觉推理。
- 坐标工具使用当前截图的 0-1000 归一化坐标，不使用设备像素坐标；不能让同一对数字
  同时被解释成像素和归一化坐标。

## Product Priority

当工程取舍冲突时，优先级如下：

1. 完成真实手机任务
2. 避免假完成
3. 能从错误动作中恢复
4. 能重规划继续推进
5. 可观测、可复盘、可 benchmark
6. 成本、延迟、代码兼容性

## Patch Policy

当实现与旧代码结构冲突时：

1. 以 `docs/architecture-design.md` 为准。
2. 不为了兼容旧模块边界牺牲完成率。
3. 如果新架构文档缺失关键规则，先补文档再改代码。
4. 如果遇到安全、权限、不可逆操作相关风险，先保留确认/阻断机制。
5. 如果某个修复需要写死 app、页面、业务文案、联系人、商品、按钮路径或固定坐标，
   默认拒绝；这些信息只能放进 benchmark scenario、oracle、fixture 或人工标注，用于评估，
   不得进入 agent runtime。
6. 如果某个修复需要把 raw XML、长 UI tree、长候选列表或候选句柄动作作为默认
   模型输入/主执行路径，默认拒绝；即使作为实验，也必须先证明它提升真实完成率且
   不增加假完成、重复动作、token 成本或超时。

## Hard Runtime Contracts

- Executor 每轮只能执行 1 个 tool call。
- Executor 不能直接宣布任务成功；`finished` 只是完成提议。
- `finished` 必须经过 `VerifierAgent + CompletionGate`。
- `VerifierAgent` 只读，不能调用工具，不能执行动作。
- 外部阻塞可通过 `Human Assistance Gate` 挂起任务并请求用户协助，用户完成后必须刷新观察并继续原任务。
- 工具结果必须进入 `WorldState`，并逐步迁移到结构化 `ToolResult`。
- Planner 负责目标理解、高层策略、成功条件和失败后重规划；不能输出坐标或固定点击脚本。
- Orchestrator / TaskController 拥有预算、恢复、重规划、终止决策。
- Executor 默认基于截图视觉理解和坐标工具完成操作；模型动作接口不得暴露候选句柄，
  也不得因为页面存在候选项就压制合法坐标点击。
- `click` / `long_press` / `drag` / `scroll` 的坐标语义必须与截图一致：x/y 是 0-1000
  归一化坐标，而不是设备像素坐标。工具提示和执行逻辑必须保持同一个坐标系。
- XML、accessibility tree、OCR 和候选项可以用于 trace、replay、oracle、调试和离线分析；
  进入模型上下文必须保持短、相关、可消融，并且经过 benchmark 验证。
- Runtime 不暴露页面专用捷径工具，例如直接打开某个 Settings 子页面的 intent 工具；这类
  能力只能用于 benchmark prep、oracle 或人工 fixture，不能计入 agent 能力。
- Executor 不得在模型行动前自动替模型启动目标 app。核心路径中 `open_app`/`resolve_installed_app`
  必须由模型选择；benchmark 可以准备初始状态，但要在报告里标注为环境准备。
- 简单 app 启动只能作为基础链路 smoke，不作为核心智能执行完成率；核心完成率必须来自
  模型视觉理解、动作选择、恢复和 Verifier/Gate 闭环。

## Current Phase

当前阶段是 `Completion-first foundation`，允许并优先建设：

1. PlannerAgent
2. ExecutorAgent
3. VerifierAgent
4. CompletionGate
5. Human Assistance Gate
6. TaskController / Orchestrator
7. WorldState
8. ScreenObserver
9. Primitive Tools
10. Structured ToolResult
11. RecoveryPolicy
12. Trace / Replay / Benchmark

当前阶段不优先建设：

1. 可复用技能学习
2. 可复用技能作为主执行路径
3. 固定 app workflow graph
4. 为已删除的旧业务模块保留兼容层
5. 大量专用人机协作工具
6. 泛化视觉坐标降级
7. 业务 app 专用补丁、页面文案特判、固定点击脚本或一次性完成率补丁
8. 默认 raw XML / 长候选列表 / 候选项优先执行策略

## Completion Rule

任务成功只能来自：

1. `VerifierAgent` 基于可观测证据接受完成；或
2. 显式、确定、可观测的非 LLM postcondition。

页面变化、模型自称完成、或最近有一次成功点击，都不等于任务完成。
