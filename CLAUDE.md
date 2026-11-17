# Tape — Claude Code Project Context

Project context lives in one file, shared with Hermes. Read it first:

@.hermes.md

## Claude-specific notes

- This file must stay at the repo root: Claude Code does not load a `CLAUDE.md` kept under
  `.vscode/`.
- Before saying a change works, run `./gradlew assembleDebug testDebugUnitTest`. The unit
  tests measure the practice room end to end, so they cover everything except the ARCore
  adapter in `ar/ArCoreFrame.kt`. That file can only be verified on a phone — say so when a
  change touches it.
