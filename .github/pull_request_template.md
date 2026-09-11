## What changed


## Why


## Validation

- [ ] `./gradlew test --continue`
- [ ] `./gradlew assembleDebug`
- [ ] Device benchmark or trace review, if runtime behavior changed

## Risk check

- [ ] Does not add app-specific runtime hardcoding
- [ ] Does not weaken completion verification
- [ ] Does not bypass safety confirmation
- [ ] Does not expose sensitive benchmark artifacts
