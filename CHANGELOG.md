# Changelog

All notable changes to Tape are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Because Tape's product promise is honesty about accuracy, entries that change
**what the app claims about a measurement** are called out explicitly — those are
user-trust changes, not just features.

---

## [Unreleased]

**Shape tools.** Tape measures objects, not only distances. See
`.hermes/plans/2026-11-16-shape-tools.md`.

### ⚠️ Accuracy claims changed
- Areas, volumes, circumferences and angles now carry tolerances derived from the spans
  they are built from (first-order propagation, in quadrature). A height set by tilting the
  phone is graded as an off-plane point, because nothing was tapped there.

### Added
- Eight tools: Line, Path, Area, Angle, Height, Circle, Box, Cylinder.
- Aim-then-tap: points go under the centre reticle, with a live preview before each tap.
- Height step: tilt the phone to raise a base into a box, cylinder or height.
- Undo, Reset and Done (closes a path or area, keeps a box or cylinder flat).
- ARCore Depth API, where the device supports it, so points can land on objects and not
  only on large planes.
- Practice room: a simulated room of known sizes, offered when ARCore can't run (including
  the Android emulator on Apple-silicon Macs). Saves from it are labelled "Practice room".
- 52 new unit tests (70 in total), 16 of them measuring the practice room end to end.

### Changed
- Schema v2 → v3 adds `kind` and `readings`; existing rows stay lines and are not back-filled.
- The ARCore availability check waits for a final answer instead of treating "still
  checking" as "unsupported".
- `CLAUDE.md` moved to the repo root, where Claude Code loads it, and now imports
  `.hermes.md` instead of duplicating it.

### Removed
- Dead code: empty `rename()`, unused `justSaved` flag, unused tokens and colours, the
  placeholder test, five unused test dependencies, duplicate root HTML prototypes.

### Planned
- Tablet / landscape layout (v1 scope item 8)
- Share measurement as image (v1 scope item 5, partial)
- Optional label prompt on save

---

## [0.2.0] — 2026-09-20

**Honest Confidence Engine.** Replaces Tape's fabricated accuracy indicator with a
real, derived error bound. This completes v1 scope item 7 and makes the
Confidence-Forward design decision (`execution.md`, 2026-08-22) actually true.

### ⚠️ Accuracy claims changed

Tape's stated accuracy is now **derived from observed AR signals** rather than
hardcoded. Measurements will report **wider tolerances than before** — especially at
long range, in low light, or on featureless surfaces. This is not a regression: the
previous figure was fixed and unearned. The new figures are what the hardware
actually supports.

### Added
- `AccuracyEngine` — derives a ± tolerance from four observable, independent error
  sources, combined in quadrature:
  - base ARCore floor (1.0%)
  - anchor placement range (0→4%, ramping between 1 m and 5 m, worst endpoint wins)
  - tracking failure reason (0→12%: low light, excessive motion, featureless surface,
    bad state)
  - anchor surface type (0→3.5% penalty when a point sits on a feature point rather
    than a detected plane)
- `AccuracyEstimate` / `AccuracyGrade` domain model — HIGH (≤2%), FAIR (2–5%),
  LOW (>5%), UNAVAILABLE (refuses to answer), UNKNOWN (pre-engine rows).
- **Refusal behaviour.** When either anchor stops tracking or the camera loses
  tracking, the app shows "Can't verify" with an explanation instead of a number.
  Anchor drift is unbounded in that state; any figure would be a guess.
- **Plain-language explanations.** Every estimate carries a sentence naming the
  dominant error source and what to do about it ("Low light is hurting tracking.
  Move somewhere brighter."). Fulfils the design-brief requirement at
  `execution.md:112`.
- Tolerance persisted with every saved measurement (`toleranceMeters`,
  `accuracyGrade`) and displayed on saved rows in the grade's colour.
- Shared measurement text now includes the ± figure — a number sent to someone else
  without its error bound is the same overclaim, travelling further.
- Room schema export (`app/schemas/`) so migrations test against the real prior
  schema rather than an approximation.
- `TESTING.md` — full verification guide including a physical-tape falsification
  procedure, written to be handed to another engineer or AI tool cold.

### Changed
- **Save is blocked when accuracy is unverifiable.** Persisting a distance whose
  error we could not bound would put an unqualified number in the saved list.
- Confidence badge reports the *reading's* grade and real tolerance once a
  measurement exists, falling back to session tracking state before that.
- Distance pill shows the tolerance directly beneath the value, colour-coded by
  grade (Confidence-Forward treatment).
- Bottom hint text is replaced by the accuracy explanation once a reading exists.
- `TrackingConfidence` labels clarified ("Tracking locked" / "Tracking recovering")
  to separate *session* tracking from *measurement* trust — good tracking does not
  by itself make a 6 m span accurate.
- A camera that is TRACKING but reporting a failure reason is now graded LOW rather
  than HIGH — it is recovering, not locked.
- Hit-testing now requires the hit pose to fall inside the plane polygon
  (`isPoseInPolygon`), rejecting hits on the infinite plane beyond its detected bounds.
- Room schema v1 → v2 via a real migration. `fallbackToDestructiveMigration()` was
  removed: saved measurements cannot be regenerated, so schema changes must preserve
  them.
- `isSaving` is now actually driven during the save coroutine (previously never set).

### Removed
- `TrackingConfidence.accuracyHint()` — returned a hardcoded `"±1 cm"` for every
  good-tracking reading regardless of distance, device, or geometry. A 6 m span on a
  budget phone reported the same accuracy as a 20 cm span on a flagship.
- Fabricated `confidence` float (`0.9f` / `0.5f` / `0.1f`) on saved measurements —
  this was the `TrackingState` enum relabelled as a float and carried no information.
  The column is dropped in the v1→v2 migration rather than migrated forward.

### Fixed
- Stale tolerance no longer persists on screen after tracking is lost.
- `androidTest` APK packaging collision (`META-INF/LICENSE*` duplicated across the
  JUnit 5 jars pulled in transitively by `room-testing`).

### Migration notes
- Schema v1 → v2 adds `toleranceMeters` and `accuracyGrade`, drops `confidence`.
- Implemented as create/copy/drop/rename rather than `ALTER TABLE ... DROP COLUMN`,
  which requires SQLite 3.35+ (Android 14 / API 34). Tape's minSdk is 24, so the
  direct form would throw at upgrade time on Android 7–13.
- Measurements saved before this release are preserved and marked
  `accuracyGrade = UNKNOWN`, displaying as "accuracy not recorded". They are
  **deliberately not back-filled** with a plausible tolerance — the tracking
  conditions they were taken under were never recorded, and inventing a figure
  retroactively would be the same dishonesty in a new place.

### Verification
- 22 JVM unit tests passing (`AccuracyEngineTest` + scenario dump + pre-existing).
- 2 instrumented migration tests passing on a real device (API 36 emulator),
  proving v1 rows survive the table rebuild with values intact.
- Key regression guards: tolerance must grow with distance, must respect the ±5 mm
  floor, displayed % must agree with displayed ±, and every estimate must carry a
  non-empty explanation.

### Known limitations
- Engine constants are literature-informed, not field-calibrated. `TESTING.md` §4.2
  is the falsification procedure — it needs a physical tape and a real room.
- No per-device tiering. A flagship and a budget phone share the same base term;
  real tiering needs field data we do not have, and inventing a device table would
  be fabrication.
- ARCore Depth API unused. ToF-equipped devices could bound error more tightly.

---

## [0.1.0] — 2026-09-14

Initial skeleton and core feature implementation.

### Added
- Jetpack Compose UI with warm-amber dark theme (`Color.kt` as palette source of truth)
- ARCore AR measurement via SceneView: two-tap point-to-point distance
- Plane detection with visual grid
- Unit switching (cm / m / in / ft) with locale-driven default
- Room database for local measurement persistence
- DataStore user preferences (default unit, theme)
- Saved measurements list with rename, delete, share
- Settings screen (default unit, theme, about)
- Navigation graph: Welcome → Permission → Measure, plus Saved and Settings
- Honest "unsupported device" screen for non-ARCore hardware
- Hilt dependency injection, IBM Plex font integration

[Unreleased]: https://github.com/Akshay2996/tape-android/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Akshay2996/tape-android/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Akshay2996/tape-android/releases/tag/v0.1.0
