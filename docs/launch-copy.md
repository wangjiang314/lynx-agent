# Launch Copy

Use these as starting points for the public launch. Edit them to match what the
repo can honestly prove on launch day.

## Repository Description

Option A:

`Completion-first Android task agent with evidence-based verification, recovery, and real-device benchmarks.`

Option B:

`An Android AI agent runtime focused on verified task completion instead of demo-only success.`

## Short Tagline

`Finish the task, verify the result, recover when wrong.`

## Release Title

`v0.1.0-preview: completion-first Android agent runtime`

## Release Notes Draft

This is the first public engineering preview of Lynx Agent.

Lynx is an Android task agent built around a completion-first idea: a task is
not done because the model says it is done. A task is done only when the
runtime can verify completion from observable evidence.

What is included in this preview:

1. planner, executor, verifier, completion gate, and human-assistance flow
2. vision-first runtime direction with minimal default context
3. benchmark suites and device benchmark scripts
4. architecture and validation guardrail docs

What is not solved yet:

1. stronger structured primitive `ToolResult` boundaries
2. a more explicit standalone `RecoveryPolicy`
3. better typed human-assistance UX for login codes and similar blockers
4. broader benchmark coverage

This release is not a polished product launch. It is a public foundation for a
more trustworthy Android agent runtime.

## GitHub Intro Post

I open-sourced `Lynx Agent`, a completion-first Android task agent.

The core idea is simple: mobile agents should not claim success just because the
executor says "finished". They should verify completion from observable
evidence, recover from wrong turns, and ask for help when a task is externally
blocked.

This preview includes the runtime direction, benchmark suites, and guardrail
docs. It is still early, but the repo is public because I want the engineering
tradeoffs to be inspectable and improve in the open.

Repo:

## X / Short Post

Open-sourced `Lynx Agent`: a completion-first Android task agent.

The main bet is that mobile agents need stronger verification and recovery, not
just better-looking demos.

Current preview includes:

1. planner / executor / verifier / completion gate flow
2. human-assistance path for external blockers
3. real-device benchmark direction

## Hacker News / Reddit Style Summary

I built and open-sourced an Android task agent runtime that focuses on verified
completion instead of demo-style success signals.

The repo is still an engineering preview, but the main direction is already
public:

1. `finished` is only a proposal
2. a verifier judges completion from observable evidence
3. a completion gate decides whether success can be reported
4. the runtime can pause for human help on external blockers
5. benchmarks are treated as first-class evidence

The long-term interest for me is not app-specific scripts. It is whether we can
make phone agents more trustworthy, measurable, and honest about failure.

## Topics

Suggested GitHub topics:

`android`, `android-automation`, `ai-agent`, `mobile-agent`, `benchmark`, `llm`, `accessibility`, `computer-vision`
