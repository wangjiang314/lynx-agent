# Contributing to Lynx Agent

Thanks for taking an interest in Lynx Agent.

This project is still an engineering preview, so the goal is not to merge every
idea quickly. The goal is to keep the runtime moving toward higher real task
completion rate without drifting into app-specific patches or benchmark tricks.

## Before You Start

Please read these first:

1. [README.md](README.md)
2. [docs/architecture-design.md](docs/architecture-design.md)
3. [docs/agent-validation-platform.md](docs/agent-validation-platform.md)
4. [AGENTS.md](AGENTS.md)

These documents explain the main contracts of the project:

1. completion-first over demo-first
2. evidence-based completion over model self-report
3. vision-first minimal context over XML-heavy default prompts
4. generic Android capability over app-specific runtime hardcoding

## Good First Contributions

Early contributions are most helpful in these areas:

1. documentation clarity
2. benchmark coverage and benchmark hygiene
3. trace readability and debugging ergonomics
4. primitive tool result structure
5. verifier and recovery tests
6. provider integration that keeps runtime behavior generic

## What We Usually Do Not Want

These changes are usually out of scope for the main runtime:

1. app-specific fixed click paths
2. hardcoded page labels, package-specific shortcuts, or benchmark-only prompts
3. direct success shortcuts that bypass `VerifierAgent + CompletionGate`
4. large context additions without benchmark evidence
5. changes that improve one scenario by making the runtime less general

If a benchmark reveals a real generic weakness, fix the generic weakness and
leave scenario knowledge in benchmarks, fixtures, or oracle logic.

## Development Flow

1. Open an issue first for large changes.
2. Keep patches small and focused.
3. Explain which architecture goal the change improves.
4. Mention what you tested.
5. Call out risks or unverified assumptions.

Useful local commands:

```bash
./gradlew test
./gradlew assembleDebug
tools/run_device_benchmark.sh --scenario-id smoke --instruction "open settings" --launch
tools/run_benchmark_suite.sh benchmarks/suites/completion_smoke.tsv
```

## Pull Request Notes

Please include:

1. what changed
2. why it helps completion, recovery, verification, or observability
3. what tests or benchmark checks you ran
4. what is still not proven

Small, well-explained PRs are much easier to review than broad rewrites.
