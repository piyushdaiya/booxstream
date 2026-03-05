# Contributing to BooxStream

Thank you for your interest in contributing.

We welcome bug fixes, improvements, documentation updates, and testing on additional Boox devices.

---

## Development Setup

### Android

```
cd android
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

### Host CLI

```
cd host/booxcpy
go test ./...
go build
```

---

## Pull Request Guidelines

Please ensure:

- CI passes
- tests are updated if behavior changes
- documentation is updated when necessary

Keep pull requests focused and small when possible.

---

## Commit Message Style

Examples:

```
android: fix encoder restart when screen rotates
booxcpy: add --record option
docs: update architecture documentation
```