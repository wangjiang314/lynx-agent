# Benchmark Results

This page summarizes selected real-device benchmark runs for Lynx Agent.
Raw benchmark artifacts are generated under `artifacts/benchmarks/` and are
ignored by git because they can contain device-specific logs, prompts, screenshot
metadata, and provider details.

The numbers below are early engineering signals. They should be read together
with trace integrity, false-completion checks, repeated-action counts, and
failure categories.

## Selected Runs

| Date | Suite | Device | Final Success | First Attempt Success | Trace Integrity | Notes |
| --- | --- | --- | ---: | ---: | --- | --- |
| 2026-06-02 | `ability_cross_app_core_v9_network_restored` | `QXNUT21B09005099` | 6 / 6 | 6 / 6 | pass | Calculator, clock stopwatch, clock timer, browser search, file manager, and notepad all passed. |
| 2026-08-12 | `completion_smoke` | `MQS0219531003849` | 3 / 5 | 3 / 5 | pass | Calculator, WLAN, and Bluetooth passed. Settings launch timed out after verifier rejection; Settings battery search failed with `NO_TOOL_CALL`. |
| 2026-08-12 | `completion_smoke` | `MQS0219531003849` | 0 / 2 | 0 / 2 | pass | Suite aborted after two environment/provider failures before useful agent execution. |

## What Counts As Success

A run is counted as successful only when one of these conditions is met:

- `VerifierAgent` accepts the finish proposal using observable screen evidence.
- `CompletionGate` accepts the verifier decision.
- A deterministic postcondition proves completion for that scenario.

The executor's own `finished` action is only a finish proposal. It is not task
success by itself.

## Metrics Captured

The benchmark scripts capture:

- terminal status and failure category
- API calls, tool calls, and token usage
- executor iterations and total Android actions
- repeated action count and repeated action ratio
- changed actions
- finish handshake rejections and recoveries
- verifier events and verifier rejections
- completion gate accepted and rejected events
- trace integrity status and reason

## Reproduce

Run the smoke suite:

```bash
tools/run_benchmark_suite.sh \
  --suite benchmarks/suites/completion_smoke.tsv \
  --attempts 2
```

Run the cross-app core suite:

```bash
tools/run_benchmark_suite.sh \
  --suite benchmarks/suites/ability_cross_app_core.tsv \
  --attempts 1
```

Use `--serial <device-id>` when multiple devices are attached.
