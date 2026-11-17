# Shape tools — how Tape measures objects

🟡 In progress

> All eight measuring tools are built and pass 70 unit tests plus 3 on-emulator migration
> tests. **None of it has run on a physical phone.** The ARCore adapter is the one part no
> test covers; the Pixel 8a run is the next real step.

**Started:** 2026-11-16
**Branch:** `feature/honest-confidence-engine` (uncommitted)
**Reference:** ARuler screen recording 2026-08-19 (paywall excluded on purpose)

## What the user does

Aim the centre reticle at a point and tap anywhere. Points go where the reticle aims, not
under the finger, because a fingertip hides the corner it is trying to hit. The shape is
solved every frame with the reticle standing in for the next point, so the measurement is
visible before it is committed.

| Tool | Taps | Height step | Reports |
|---|:--:|:--:|---|
| Line | 2 | — | length |
| Path | any, then Done | — | total length |
| Area | any, then Done | — | area, perimeter |
| Angle | 3 (arm, corner, arm) | — | degrees |
| Height | 1 | ✅ | height |
| Circle | 2 opposite rim points | — | diameter, circumference, area |
| Box | 3 corners of one face | ✅ (Done keeps it flat) | volume, length, width, height, surface |
| Cylinder | 2 opposite rim points | ✅ | volume, diameter, height, surface |

**Height step.** The top of an object is usually an edge in mid-air with nothing to hit-test.
Tape extends the base into an imaginary wall (a vertical plane, a box's four sides, or a
cylinder's curved side) and reads how high up that wall the reticle points. A box can be
sketched on its visible top face and pulled down to the floor.

## How it is built

```text
domain/            pure Kotlin, JVM-testable, no ARCore imports
  ar/ArFrame         the seam: tracking state, matrices, hitTest → SurfaceHit → Pin
  geometry/          Vec3, Projector (world ↔ screen, near-plane clipping)
  shape/             MeasureTool, MeasureSession (state machine), ShapeSolver, Lift
  accuracy/          AccuracyEngine (unchanged error model)
ar/ArCoreFrame       ArFrame on a phone: depth enabled, nearest hit with plane preference
sim/                 ArFrame in the practice room: SimWorld (known sizes), SimFrame, SimCamera
```

**Accuracy.** Each span between placed points is bounded by `AccuracyEngine` exactly as
before. Derived figures (area, volume, circumference) get their bound by first-order error
propagation in quadrature. Polygon area uses a conservative bound (twice the worst side's
relative error). The tilted height is graded as an off-plane point because nothing was tapped.

**Saving.** Schema v3 adds `kind` and `readings` (JSON). Old rows stay lines; nothing is
back-filled. Practice-room saves are labelled "Practice room".

## Why the emulator shows a practice room

| Fact | Evidence |
|---|---|
| ARCore's emulator support is x86-only | Google's emulator guide: arm64-v8a "not currently supported" |
| This Mac is Apple silicon; the AVD is arm64 | `uname -m` → arm64; AVD `abi.type=arm64-v8a` |
| ARCore 1.57 installs but cannot open a session | logcat: `Unable to retrieve camera characteristics for unknown device 0` |

So the emulator falls through to the Unsupported screen, which offers the practice room. The
room runs the same session, solver and overlay code as a phone; only `ArFrame` differs.

## Verification

| Check | Result |
|---|:--:|
| `testDebugUnitTest` — 70 tests incl. 16 end-to-end practice-room measurements | ✅ |
| Truth inside the stated tolerance for every practice-room tool | ✅ |
| Mutation: break box volume by 10% → 3 tests fail | ✅ |
| `connectedDebugAndroidTest` — migrations v1→v2, v2→v3, writes after both | ✅ |
| `lintDebug` — 0 errors | ✅ |
| Manual on emulator: all eight tools, save, saved list | ✅ |
| Real ARCore on Pixel 8a | ❌ not run |

## Challenges that are not hardware

| Challenge | Status |
|---|---|
| Emulator can't run ARCore on Apple silicon | Worked around: practice room |
| Tops of objects have nothing to hit | Solved: height step against an imaginary wall |
| Tiny or featureless objects give no hit | Depth API enabled; unverified on device |
| Plane vs depth hit choice on box tops | Nearest hit, plane preferred within 3 cm / 3%; unverified |
| Engine constants not field-calibrated | Open: needs the tape-measure run in `TESTING.md` §4.2 |
| A rim point that misses a small, distant top lands on the floor behind it | Open: graded Unreliable, but the number is wildly off. Guard: reject a circle whose two points differ in height by more than a fraction of their span |

## Next steps

1. Install on the Pixel 8a and run every tool against a physical tape (`TESTING.md`).
2. Tune `PLANE_PREFERENCE_*` and the level-snap angles from that run.
3. Fit Check (body scan) — design in `.hermes/plans/local/`, built after step 1.
