# Open Source Release Checklist

Use this checklist before publishing the first public version of Lynx Agent.

## 1. Repository Hygiene

- [x] No real API keys remain in code, tests, docs, or screenshots
- [x] No private endpoint URLs remain in code or docs
- [x] No local machine paths remain in public-facing docs
- [x] No `local.properties`, build outputs, or benchmark artifacts are included
- [x] Placeholder defaults are clearly placeholders

## 2. Build And Verification

- [x] `./gradlew test` passes
- [x] `./gradlew assembleDebug` passes
- [x] At least one device benchmark run is recent enough to discuss honestly
- [x] Known limitations are documented in `README.md`

## 3. First-Visit Experience

- [x] The first screen of `README.md` explains what Lynx is in under 30 seconds
- [x] `README.md` explains what is different about completion-first execution
- [x] `README.md` shows the basic build and benchmark commands
- [x] `ROADMAP.md` reflects the actual next priorities
- [x] `CONTRIBUTING.md` tells outside contributors where to help

## 4. Demo And Evidence

- [x] Record one short demo video
- [x] Prepare one benchmark snapshot you are comfortable sharing
- [x] Be ready to explain one failure case, not only one success case

## 5. GitHub Setup

- [x] Create the public repository
- [x] Add repository description
- [x] Add repository topics
- [x] Push the initial `main` branch
- [x] Add the first release notes or pinned discussion

## 6. Suggested Initial Topics

Pick a small set that matches the repo honestly:

- `android`
- `android-automation`
- `ai-agent`
- `mobile-agent`
- `accessibility`
- `benchmark`
- `computer-vision`
- `llm`

## 7. Suggested First Release Shape

Recommended label:

`v0.1.0-preview`

Recommended meaning:

1. engineering preview
2. completion-first runtime foundations are public
3. benchmark and verification direction is visible
4. not yet a polished consumer product

## 8. After Launch

Within the first week:

- [ ] Fix obvious README confusion fast
- [ ] Reply to issues with warmth and clarity
- [ ] Label newcomer-friendly issues
- [ ] Post one follow-up note with benchmark progress or lessons learned
