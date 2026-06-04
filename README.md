# Lynx Agent

Lynx Agent is a completion-first Android task agent.

Its goal is simple: finish real phone tasks from natural-language instructions,
recover from wrong turns, and avoid claiming success without observable
evidence.

This repository is an engineering preview. The current focus is not app-specific
automation scripts or fixed workflow graphs. The focus is a general Android
agent runtime with:

1. evidence-based completion
2. recovery after wrong actions
3. human assistance for external blockers
4. benchmarkable task execution on real devices

The architecture truth lives in [docs/architecture-design.md](docs/architecture-design.md).
The development validation guardrails live in
[docs/agent-validation-platform.md](docs/agent-validation-platform.md).

## What Makes It Different

Lynx follows a completion-first runtime:

1. `PlannerAgent` turns the user instruction into a high-level `TaskSpec`.
2. `ExecutorAgent` chooses exactly one phone action per round.
3. `WorldState` stores observations, actions, outcomes, criteria, and evidence.
4. `VerifierAgent` judges completion from current observable state.
5. `CompletionGate` accepts or rejects every finish proposal.
6. `AgentOrchestrator` owns budgets, recovery, replanning, and terminal states.
7. `HumanAssistanceGate` pauses for login, verification, permissions, or other
   external blockers.

The runtime is vision-first and minimal-context by default. Screenshots and a
small amount of recent task state are the primary execution path. XML, OCR, UI
trees, and candidate lists are useful for trace, replay, oracle, debugging, and
ablation, but they are not the default action protocol.

## Current Status

The current implementation already includes the first completion-first slice:

1. planner output carries `goal`, `target_app`, `strategy`,
   `success_criteria`, and `risk_hints`
2. `finished` is a proposal, not a success signal
3. `VerifierAgent + CompletionGate` decides whether a task is actually done
4. verifier decisions, evidence, missing evidence, and assistance events are
   persisted in `WorldState`
5. external blockers can suspend the run and resume after human help

Important current limitation:

The model/data layer supports `USER_TEXT` assistance requests, but the app-level
assistance UX is still closer to a confirmation channel than a full typed-text
handoff path.

## Build

Prerequisites:

1. Android Studio 2023.1 or newer
2. Android SDK 35
3. Java 11 or newer
4. Kotlin 1.9.20

Build and test:

```bash
./gradlew assembleDebug
./gradlew test
```

Install on a connected device:

```bash
./gradlew installDebug
```

## Configuration

Lynx supports:

1. a standard OpenAI-compatible chat completions endpoint
2. a custom OpenAI-compatible endpoint

The checked-in defaults are placeholders only. Configure the API base URL, API
key, text model, and vision model in the app settings screen before running the
agent.

## Benchmarks

The repository includes benchmark suites under [benchmarks](benchmarks) and helper scripts under [tools](tools).

Run the Gradle suite first:

```bash
./gradlew test --continue
```

Run a manual device benchmark:

```bash
tools/run_device_benchmark.sh --scenario-id smoke --instruction "open settings" --launch
```

Run a benchmark suite:

```bash
tools/run_benchmark_suite.sh benchmarks/suites/completion_smoke.tsv
```

## Roadmap

The next engineering priorities are:

1. move primitive tools to a stricter structured `ToolResult` boundary
2. extract a small dedicated `RecoveryPolicy`
3. improve typed human-assistance flows for login codes and external blockers
4. expand benchmark coverage without adding app-specific runtime hardcoding
5. use benchmark evidence to decide whether extra observation channels help

The longer-form roadmap lives in [ROADMAP.md](ROADMAP.md).

The launch checklist lives in [docs/open-source-release-checklist.md](docs/open-source-release-checklist.md).

Draft launch copy lives in [docs/launch-copy.md](docs/launch-copy.md).

## Contributing

Contribution guidelines live in [CONTRIBUTING.md](CONTRIBUTING.md).

Security reporting guidance lives in [SECURITY.md](SECURITY.md).

Community expectations live in [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
