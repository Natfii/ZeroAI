<!-- Copyright (c) 2026 @Natfii. All rights reserved. -->

# Contributing

Thanks for your interest in Zero.

## Current status

Public collaboration will be reconsidered later, for now please fork for your own patches. Please do not open upstream ZeroClaw Labs issues for behavior that comes from this downstream Android fork.

## If contributing opens

If you are evaluating the project locally:

- keep signing files, local properties, and other machine-specific secrets outside the repo
- read [README.md](README.md) for build prerequisites and repository layout
- follow the Kotlin and Rust conventions documented in [CLAUDE.md](CLAUDE.md)

## Future contribution expectations

If/Whem issue tracking and pull requests open, contributions will be expected to:

- stay narrowly scoped
- preserve Android API 28 compatibility
- keep Rust panics contained at the FFI boundary
- include tests or verification notes for behavior changes
