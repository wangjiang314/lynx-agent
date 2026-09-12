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

Repo: https://github.com/wangjiang314/lynx-agent

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

## Chinese Launch Post

我把 Lynx Agent 开源了：一个 Android 手机操作 Agent，重点不是把 demo 做得好看，而是回答一个更硬的问题：模型说“完成了”，到底是不是真的完成？

Lynx 的核心设计是 completion-first：`finished` 只是一条完成提议，不能直接算成功。系统会把当前屏幕、OCR、Accessibility 信息和任务目标交给只读的 VerifierAgent，再由 CompletionGate 判断能不能报告成功。如果证据不够，就继续执行、恢复或请求人工协助。

这次公开的是 engineering preview，不是消费级产品。仓库里包括：

1. Planner / Executor / Verifier / CompletionGate 的运行链路
2. Android Accessibility + MediaProjection 的真机操作基础
3. 真实设备 benchmark 脚本和 trace 记录
4. 一个设置 WLAN 页面的真机 demo
5. debug APK 预览包，方便愿意折腾的人快速试用

我做这个项目的原因很简单：移动 Agent 不能只靠“点到了某个按钮”或“模型自称完成”来证明成功。真正有用的手机 Agent，应该能验证结果、承认失败、从错误动作里恢复，并留下可复盘的证据。

Repo: https://github.com/wangjiang314/lynx-agent
Release: https://github.com/wangjiang314/lynx-agent/releases/tag/v0.1.0-preview
