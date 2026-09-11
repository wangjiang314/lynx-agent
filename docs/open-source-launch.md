# Open Source Launch Notes

Use this document to finish the public GitHub packaging for Lynx Agent.

## GitHub About

Description:

```text
Completion-first Android phone-use agent with evidence-based verification, recovery, safety checks, and real-device benchmarks.
```

Website:

```text
https://github.com/wangjiang314/lynx-agent
```

Topics:

```text
android
android-automation
ai-agent
mobile-agent
phone-use
computer-use
accessibility
ocr
llm
benchmark
kotlin
android-agent
```

## Release Title

```text
v0.1.0-preview: completion-first Android phone-use agent
```

## Release Notes

```markdown
This preview release introduces Lynx Agent, a completion-first Android phone-use
agent focused on real-device task completion, recovery, and false-success
prevention.

Highlights:

- Vision-first Android operation through screenshots and normalized coordinates.
- Accessibility and MediaProjection based device control, no root required.
- Planner, executor, verifier, completion gate, world state, and safety guard.
- `finished` is treated as a proposal, then checked by VerifierAgent and
  CompletionGate before success is reported.
- Real-device benchmark scripts for smoke and cross-app suites.

This is an engineering preview for Android agent runtime research and
experimentation. It is not a polished consumer assistant.
```

## Demo Scripts

Record short videos before posting widely. Keep each clip under 30 seconds.

### Demo 1: Settings WLAN

Instruction:

```text
打开设置并进入 WLAN 页面
```

What to show:

- Lynx receives the instruction.
- It opens Settings.
- It reaches the WLAN page.
- The verifier accepts observable completion.

### Demo 2: Browser Search

Instruction:

```text
打开浏览器搜索 OpenAI，并停留在搜索结果页
```

What to show:

- App launch or app resolution.
- Search input.
- Search results visible.
- Agent stops without opening a result page.

### Demo 3: Notepad Draft

Instruction:

```text
打开记事本，新建一条多行草稿，内容是：今天测试 Lynx Agent\n它会验证完成状态
```

What to show:

- Multi-line input.
- Final visible text.
- Completion gate acceptance.

## Short Social Post

```text
I open-sourced Lynx Agent, an Android phone-use agent focused on one problem that
mobile-agent demos often skip: how do you know the task is actually complete?

Instead of treating "finished" as success, Lynx routes every finish proposal
through a read-only verifier and completion gate. It also records real-device
benchmark traces for recovery, repeated actions, token usage, and false-success
review.

Repo: https://github.com/wangjiang314/lynx-agent
```

## Chinese Post

```text
我开源了 Lynx Agent，一个 Android 手机操作 Agent。

它的重点不是“让大模型点手机屏幕”这么简单，而是解决一个更麻烦的问题：模型说完成了，
到底是不是真的完成？

Lynx 把 finished 当成完成提议，再交给 VerifierAgent 和 CompletionGate 根据当前屏幕证据
判断是否接受。项目里也包含真机 benchmark、trace integrity、失败分类、重复动作统计和
安全确认。

仓库：https://github.com/wangjiang314/lynx-agent
```
