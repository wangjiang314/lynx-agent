# Open Source Release Checklist

Use this checklist before publishing the first public version of Lynx Agent.

## 1. Repository Hygiene

- [ ] No real API keys remain in code, tests, docs, or screenshots
- [ ] No private endpoint URLs remain in code or docs
- [ ] No local machine paths remain in public-facing docs
- [ ] No `local.properties`, build outputs, or benchmark artifacts are included
- [ ] Placeholder defaults are clearly placeholders

## 2. Build And Verification

- [ ] `./gradlew test` passes
- [ ] `./gradlew assembleDebug` passes
- [ ] At least one device benchmark run is recent enough to discuss honestly
- [ ] Known limitations are documented in `README.md`

## 3. First-Visit Experience

- [ ] The first screen of `README.md` explains what Lynx is in under 30 seconds
- [ ] `README.md` explains what is different about completion-first execution
- [ ] `README.md` shows the basic build and benchmark commands
- [ ] `ROADMAP.md` reflects the actual next priorities
- [ ] `CONTRIBUTING.md` tells outside contributors where to help

## 4. Demo And Evidence

- [ ] Record one short demo video
- [ ] Prepare one benchmark snapshot you are comfortable sharing
- [ ] Be ready to explain one failure case, not only one success case

## 5. GitHub Setup

- [ ] Create the public repository
- [ ] Add repository description
- [ ] Add repository topics
- [ ] Push the initial `main` branch
- [ ] Add the first release notes or pinned discussion

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
