# Honest Confidence Engine — Execution Plan

**Date:** 2026-09-20
**Branch:** `feature/honest-confidence-engine`
**Base:** `main` @ cf01a06
**Status:** in progress
**Scope item:** v1 scope #7 — "Honest accuracy indicator"

---

## Why this feature

v1 scope (`execution.md`) lists eight items. Seven are built. Item 7 — the honest
accuracy indicator — is **present in the UI but fabricated in the logic**.

Two concrete lies in the current code:

| Location | Lie |
|---|---|
| `MeasureUiState.kt:39-43` | `accuracyHint()` returns a hardcoded `"±1 cm"` for any HIGH-tracking reading, regardless of distance, device, or geometry. A 6 m span on a budget phone reports the same ±1 cm as a 20 cm span on a Pixel. |
| `MeasureViewModel.kt:189-193` | Saved `confidence` is `0.9f / 0.5f / 0.1f` — the `TrackingState` enum relabelled as a float. It carries zero extra information and pretends to be a measured quality score. |

This is the exact behaviour Tape exists to replace. `.hermes.md` design principle 3
("Honest about accuracy. Show confidence badges. Explain errors. Don't oversell
precision.") is listed as non-negotiable, and `execution.md:109` makes
Confidence-Forward the **default** AR treatment — i.e. accuracy honesty *is* the
product wedge. Shipping a fabricated number is worse than shipping no number.

Chosen over the other gaps (save-label prompt, share-as-image, tablet layout) because
those are missing conveniences; this is a missing truth. It is also pure Kotlin
math — fully unit-testable on the JVM with no ARCore device required.

---

## What "honest" means here — the error model

Tape cannot know true error. It *can* bound it from things it actually observes.
The engine composes four real, observable error sources into a single ± tolerance.

### Inputs (all observable from ARCore, no invention)

| Input | Source | Why it affects error |
|---|---|---|
| `distanceMeters` | anchor A↔B euclidean | Depth error grows with range; a fixed ±1 cm at 6 m is not physically defensible. |
| `trackingState` | `Camera.trackingState` | PAUSED/STOPPED = pose drift, anchors may have moved since placement. |
| `trackingFailureReason` | `Camera.trackingFailureReason` | Distinguishes *why* tracking is bad (low light vs. excessive motion vs. featureless surface) — each is a different, explainable error magnitude. |
| `hitPoseDistance` per anchor | `HitResult.distance` at placement | Anchor placed 5 m away is less certain than one placed 0.5 m away. |
| `planeAnchorConfidence` | whether the hit trackable was a tracking `Plane` vs a depth/feature point | Plane hits are substantially more reliable than point-cloud hits. |
| anchor `TrackingState` at read time | `Anchor.trackingState` | An anchor that stopped tracking has unbounded drift — refuse to report a tolerance at all. |

### Error composition

Errors are combined in quadrature (RSS), because they are independent sources:

```
totalRelative = sqrt( baseRel^2 + rangeRel^2 + trackingRel^2 + placementRel^2 )
toleranceMeters = max(FLOOR_METERS, distance * totalRelative)
```

- `baseRel` — ARCore's irreducible floor, ~1.0 %.
- `rangeRel` — grows with anchor placement range; ARCore depth quality degrades
  beyond ~2 m. Modeled as a piecewise ramp, 0 % at ≤1 m rising to ~4 % at ≥5 m.
- `trackingRel` — 0 % when TRACKING with no failure reason; penalties per
  `TrackingFailureReason` (insufficient features, excessive motion, insufficient
  light, bad state).
- `placementRel` — penalty when an endpoint was placed on a non-plane trackable.
- `FLOOR_METERS` — 0.005 m. We never claim better than ±5 mm; no phone earns it.

**Refusal case:** if either anchor is not TRACKING, we emit
`AccuracyEstimate.Unavailable` and the UI shows "Can't verify — re-place points"
instead of a number. Refusing to answer is the honest option.

### Grades (thresholds from execution.md:188)

| Grade | Relative tolerance | Badge |
|---|---|---|
| `HIGH` | ≤ 2 % | green, "Good" |
| `FAIR` | 2–5 % | amber, "Lower accuracy" |
| `LOW` | > 5 % | red, "Unreliable" |
| `UNAVAILABLE` | n/a | red, "Can't verify" |

Note: `execution.md` phrases these as confidence percentages (<70 % amber, <40 % red).
Tolerance is the inverse framing and is what the Confidence-Forward design leads with;
the boundaries are chosen to land on the same three buckets.

### Plain-language explanation

Every estimate carries a `reason` string built from the dominant error term, e.g.
> "Both points on locked planes, 1.4 m apart. Expect ±3 cm."
> "Low light is hurting tracking. Expect ±11 cm — move somewhere brighter."

This is the design-brief requirement (`execution.md:112`) and is currently absent.

---

## Task breakdown

| # | Task | Files | Verify |
|---|---|---|---|
| T1 | `AccuracyEstimate` domain model — grade, tolerance, relative, reason, `format()` | new `domain/model/AccuracyEstimate.kt` | compiles |
| T2 | `AccuracyEngine` — pure function, no Android/ARCore imports | new `domain/accuracy/AccuracyEngine.kt` | compiles |
| T3 | Unit tests for the engine — boundaries, monotonicity, refusal, floor | new `test/.../AccuracyEngineTest.kt` | `testDebugUnitTest` green |
| T4 | Capture real ARCore signals at tap + frame time | `MeasureViewModel.kt` | compiles |
| T5 | Wire estimate into `MeasureUiState`; drop fake `accuracyHint()` | `MeasureUiState.kt` | compiles |
| T6 | Confidence-Forward HUD: tolerance leads, value secondary, reason line | `MeasureScreen.kt` | visual on device |
| T7 | Persist real tolerance + grade instead of fabricated float | `MeasurementEntity.kt`, DB v2 migration, `MeasurementRepository.kt` | migration test |
| T8 | Show grade badge on saved rows | `SavedListScreen.kt`, `SavedViewModel.kt` | visual on device |
| T9 | Docs: TESTING.md + CHANGELOG.md | new files | n/a |

**Ordering rationale:** T1–T3 first so the math is proven by tests before any AR
plumbing touches it. T7 (schema change) after the model is stable so the migration
is written once.

---

## Room migration note

`DatabaseModule.kt:22` currently uses `.fallbackToDestructiveMigration()`. Bumping
to v2 would silently wipe saved measurements. T7 writes a **real** `Migration(1,2)`
adding `toleranceMeters: Float` and `accuracyGrade: String`, with defaults
backfilled for existing rows (legacy rows get grade `UNKNOWN` — we do not
retroactively invent a tolerance for measurements taken before the engine existed;
that would be the same lie in a new place).

---

## Out of scope for this branch

- Tablet / landscape layout (v1 scope #8) — separate branch
- Share-as-image (v1 scope #5 partial) — separate branch
- Save-label prompt dialog — separate branch
- Per-device tier calibration from a hardware DB — needs field data we don't have;
  inventing a device table would be fabrication

---

## Definition of done

- [ ] `./gradlew assembleDebug` green
- [ ] `./gradlew testDebugUnitTest` green, engine tests included
- [ ] `./gradlew lint` no new errors
- [ ] No hardcoded accuracy strings remain (`grep -r "±1 cm"` returns nothing)
- [ ] `TESTING.md` written, device-testable by a third party
- [ ] `CHANGELOG.md` written
- [ ] Pushed to `origin/feature/honest-confidence-engine`
