# Roadmap

This roadmap is intentionally narrow.

Lynx Agent is not trying to become a giant app-specific automation catalog.
The near-term goal is a more trustworthy Android task agent with better
completion judgment, recovery, and benchmark evidence.

## Current Phase

The project is in an engineering preview phase focused on completion-first
runtime foundations.

What already exists:

1. planner output with task goal, target app, success criteria, and risk hints
2. finish proposals routed through `VerifierAgent + CompletionGate`
3. `WorldState` persistence for evidence, missing evidence, and assistance events
4. benchmark suites and device benchmark scripts

## Next Priorities

### 1. Structured Tool Results

Goal:

Make primitive tool execution easier to reason about by moving away from raw
string interpretation and toward a stronger `ToolResult` boundary.

Why it matters:

1. cleaner recovery decisions
2. clearer verifier context
3. better trace analysis
4. fewer hidden side effects

### 2. Recovery Policy

Goal:

Extract a small, explicit `RecoveryPolicy` that owns retry, replan, ask-human,
and abort decisions.

Why it matters:

1. less duplicated stuck logic
2. fewer accidental loops
3. easier benchmark-driven tuning

### 3. Human Assistance UX

Goal:

Improve flows where the agent needs help with login, verification codes,
permissions, or other external blockers.

Why it matters:

1. real phone tasks often hit external blockers
2. a good assist flow is more useful than fake full automation
3. typed handoff for `USER_TEXT` remains an important gap

### 4. Benchmark Expansion

Goal:

Expand benchmark coverage while keeping runtime behavior generic.

Why it matters:

1. more reliable measurement
2. better regression detection
3. less temptation to judge progress from one-off demos

### 5. Observation Ablation

Goal:

Use benchmark evidence to decide whether OCR, XML, UI text, or compact
summaries help the runtime.

Why it matters:

1. more signal, less prompt noise
2. better vision-first discipline
3. fewer premature architecture assumptions

## Explicit Non-Goals For Now

These are intentionally de-prioritized:

1. large app-specific workflow graphs
2. benchmark-only runtime shortcuts
3. model-visible candidate-handle action protocols
4. complex skill mining before generic completion gets stronger
5. broad platform abstraction before the Android runtime is more mature

## What "Better" Means

Progress should show up in measurable ways:

1. higher completion rate
2. lower false-success rate
3. lower loop or stuck rate
4. clearer recovery traces
5. better handling of externally blocked tasks
