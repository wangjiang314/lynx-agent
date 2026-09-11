# Contributing

Thanks for your interest in Lynx Agent.

This project is focused on improving general Android agent capability: task
completion, recovery, verification, safety, observation quality, traceability,
and benchmark reliability.

## Good Contributions

- Improve screenshot, OCR, UI tree, or screen-state observation.
- Improve executor prompts, action validation, tool results, or recovery.
- Reduce false completion or repeated no-progress actions.
- Add benchmark scenarios that expose general agent weaknesses.
- Add tests for verifier, completion gate, safety, coordinate handling, or
  world-state transitions.
- Improve documentation, setup, demo scripts, or failure reports.

## Contributions To Avoid

- Hardcoded click scripts for one app or one page.
- Special cases for a specific button label, contact, product, setting, or
  benchmark scenario inside runtime code.
- Fixed coordinates tuned to one device.
- Runtime shortcuts that jump directly to an app subpage.
- Large UI tree or OCR dumps in the default model prompt without benchmark
  evidence that they improve completion rate.

Scenario knowledge belongs in benchmark definitions, fixtures, oracle logic, or
manual review notes.

## Development

Build the debug APK:

```bash
./gradlew assembleDebug
```

Run unit tests:

```bash
./gradlew test --continue
```

Run a single benchmark scenario:

```bash
tools/run_device_benchmark.sh \
  --scenario-id settings_wlan \
  --instruction "打开设置并进入 WLAN 页面" \
  --duration 150 \
  --launch
```

## Pull Requests

Please keep pull requests focused. Include:

- the problem being fixed
- the runtime area changed
- tests or benchmark evidence
- any risk to completion verification, safety, or user confirmation

If a change touches `VerifierAgent`, `CompletionGate`, `HumanAssistanceGate`,
`WorldState`, safety checks, recovery budgets, or benchmark acceptance logic,
call that out clearly in the PR description.
