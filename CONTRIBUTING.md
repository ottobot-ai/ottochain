# Contributing to Ottochain

Thank you for your interest in contributing to Ottochain! This document provides guidelines and information for contributors.

## Getting Started

### Prerequisites

- JDK 11+
- Scala 2.13
- sbt 1.x
- Node.js 18+ (for e2e tests)

### Building the Project

```bash
sbt compile
```

### Running Tests

```bash
sbt test
```

## How to Contribute

### Reporting Issues

- Search existing issues before creating a new one
- Use a clear, descriptive title
- Include steps to reproduce for bugs
- Include Scala/sbt versions and relevant environment details

### Pull Requests

1. Fork the repository
2. Create a feature branch from `main`
3. Make your changes following the code style guidelines below
4. Add tests for new functionality
5. Ensure all tests pass
6. Submit a pull request

### Code Style

This project follows functional Scala conventions:

- **Purely functional** - no side effects outside the effect system
- **Tagless-final** style with `cats.effect.IO`
- Format code with `sbt scalafmtAll` before committing
- Run `sbt scalafix` for linting

### Commit Messages

- Use present tense ("Add feature" not "Added feature")
- Keep the first line under 72 characters
- Reference issues when applicable

## Project Structure

```
modules/
├── models/        # Domain types and state machine definitions
├── shared-data/   # Core state machine processing logic
├── shared-test/   # Test utilities and generators
├── l0/            # Metagraph L0 layer
├── l1/            # Currency L1 layer
└── data_l1/       # Data L1 layer
```

## Questions?

Open an issue for questions about contributing or the codebase.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.