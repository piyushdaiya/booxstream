# Contributing to BooxStream

Thank you for your interest in contributing to **BooxStream**.

BooxStream is an open-source Android screen streaming and host client tool
designed for Boox and Android-based e-ink devices.

We welcome contributions of all types:
- bug fixes
- new features
- documentation improvements
- CI/CD improvements
- performance optimizations
- testing

---

## Development Environment

### Requirements
- JDK 21
- Android SDK
- Go >= 1.22
- Git

---

## Repository Structure

android/        Android app (MediaProjection + encoder)
host/booxcpy/   Host client written in Go
.github/        CI and workflow configuration

---

## Build Instructions

### Android

cd android
./gradlew assembleDebug

Run tests:
./gradlew test

---

### Host client (Go)

cd host/booxcpy
go build

Run tests:
go test ./...

---

## Code Style

### Kotlin
- Prefer immutability
- Descriptive naming
- Avoid overly complex functions
- Prefer composition over inheritance

### Go
Run:
go fmt ./...
go vet ./...

---

## Commit Message Style

Use clear and concise commit messages.

Format:
component: short description

Examples:
stream: improve VP8 encoder recovery
ci: fix windows packaging
ui: add host connection state indicator

---

## Pull Requests

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Ensure CI passes
5. Submit a pull request

---

## Reporting Bugs

Please include:
- device model
- Android version
- host OS
- reproduction steps
- logs if available

---

## Feature Requests

Open a GitHub issue describing:
- the problem
- the proposed solution
- alternative approaches if applicable

---

Thank you for helping improve BooxStream!