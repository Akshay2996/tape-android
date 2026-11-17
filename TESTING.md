# Testing — Honest Confidence Engine

**Feature branch:** `feature/honest-confidence-engine`
**Applies to:** Tape v0.2.0
**Audience:** any engineer or AI tool picking this up cold. No prior context assumed.

---

## 1. What this feature is

Tape now derives a **real error bound** for every measurement instead of printing a
fixed accuracy string.

Before this branch:

- `MeasureUiState.accuracyHint()` returned a hardcoded `"±1 cm"` whenever camera
  tracking was good — the same claim for a 20 cm span and a 6 m span, on a flagship
  or a budget phone.
- Saved rows stored `confidence = 0.9f / 0.5f / 0.1f`, which was just the ARCore
  `TrackingState` enum relabelled as a float. It carried no information.

After this branch:

- `AccuracyEngine` composes four observable error sources into a ± tolerance that
  moves with distance, placement range, surface type, and tracking failure reason.
- When the error can't be bounded, the app **refuses to show a number** and says why.
- The tolerance is persisted, displayed on saved rows, and included in shared text.

The whole point is falsifiability: "±3 cm" is a claim you can check with a physical
tape. "92% confident" is not.

---

## 1a. Trying the shape tools on a Mac emulator

ARCore cannot run on the Android emulator on an Apple-silicon Mac, so the emulator shows the
**practice room**: a simulated room with a crate (60 × 40 × 35 cm), a drum (⌀ 40 cm, 50 cm
tall), a door outline (90 × 200 cm) and a rug outline (120 × 80 cm).

```bash
# boot the emulator (leave this running)
/opt/homebrew/share/android-commandlinetools/emulator/emulator -avd tape_pixel &

# build and install
./gradlew installDebug
```

In the app: **Continue → Allow camera access → Try the practice room**.

| Gesture | Does |
|---|---|
| Drag anywhere | Turn the phone |
| Arrow pad (top right) | Walk; the two lower buttons raise or lower the phone |
| Tap anywhere | Place a point where the centre reticle is |
| Tool chips (bottom) | Pick a tool; scroll them sideways for Circle, Box, Cylinder |

To measure the crate with **Box**: tap three corners of its top face, then drag down until
the reticle is at the bottom of a side face, and tap. Expect about 0.084 m³ (3.0 ft³).

The room's numbers are simulated. They check the app's maths and flow, not the phone.

---

## 2. The error model (what you need to know to test it)

```
totalRelative = sqrt(base² + range² + tracking² + placement²)
tolerance     = max(5 mm, distance × totalRelative)
```

| Term | Source | Effect |
|---|---|---|
| base 1.0% | ARCore's irreducible floor | always present |
| range 0→4% | worst of the two anchors' placement distance, ramping 1 m→5 m | further placement = wider tolerance |
| tracking 0→12% | `TrackingFailureReason` | low light / motion / featureless surface widen it |
| placement 0→3.5% | anchor on feature point instead of a plane | off-plane points widen it |

Grades: **HIGH** ≤2%, **FAIR** 2–5%, **LOW** >5%, **UNAVAILABLE** (refuses).

Errors combine in quadrature, not by addition — they're independent sources, and
adding them would systematically overstate error.

**Floor:** never claims better than ±5 mm, however good conditions look.

---

## 3. Automated tests

### 3.1 JVM unit tests — no device needed

```bash
./gradlew testDebugUnitTest
```

Expected: **22 tests, 0 failures.**

Reports: `app/build/reports/tests/testDebugUnitTest/index.html`

| Test class | Covers |
|---|---|
| `AccuracyEngineTest` (20) | refusal cases, distance monotonicity, the ±5 mm floor, grade boundaries, surface penalty, worst-endpoint rule, range saturation, explanation presence, formatting |
| `AccuracyEngineScenarioTest` (1) | prints a readable table of real-world scenarios (see 3.2) |
| `ExampleUnitTest` (1) | pre-existing sanity check |

Key regression guards — if any of these fail, the dishonesty has returned:

- `tolerance grows with distance instead of staying fixed` — the direct test for the
  old hardcoded `±1 cm` bug.
- `never claims better than the 5mm floor`
- `displayed percentage agrees with displayed tolerance after flooring`
- `every estimate carries a non-empty explanation`

Run just the engine:

```bash
./gradlew testDebugUnitTest --tests "com.tape.measure.domain.accuracy.*"
```

### 3.2 Eyeball the actual numbers

```bash
./gradlew testDebugUnitTest --tests "*AccuracyEngineScenarioTest*" -i --rerun-tasks \
  | grep -A 30 "ACCURACY ENGINE"
```

Prints what Tape claims in realistic situations. Verified output:

```
SCENARIO                                            SPAN TOLERANCE    REL  GRADE
----------------------------------------------------------------------------------
Laptop width, close up, good light               35.0 cm   ±0.5 cm     1%   HIGH
Doorway width (the 'will the sofa fit' case)     82.0 cm   ±0.8 cm     1%   HIGH
Sofa length, arm's reach                        210.0 cm   ±2.3 cm     1%   HIGH
Living room wall, standing back                 600.0 cm  ±16.2 cm     3%   FAIR
Same wall, but dim evening light                600.0 cm  ±34.1 cm     6%    LOW
Blank white wall (no texture to lock onto)      300.0 cm  ±27.3 cm     9%    LOW
Rushing, phone waving around                    150.0 cm  ±12.1 cm     8%    LOW
Ceiling height, points on feature cloud         270.0 cm  ±10.8 cm     4%   FAIR
```

Every row above previously read `±1 cm`.

**Sanity check when retuning constants:** these numbers should stay physically
defensible. A ±16 cm claim on a 6 m wall is honest; ±1 cm is not.

### 3.3 Room migration test — needs a device/emulator

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tape.measure.data.db.MeasurementMigrationTest
```

Note: `connectedAndroidTest` does **not** accept `--tests`; use the
`-Pandroid.testInstrumentationRunnerArguments.class=` form above.

Proves schema v1→v2 preserves saved measurements. This matters because the migration
rebuilds the table (SQLite <3.35 has no `DROP COLUMN`, and minSdk is 24), and a
rebuild that silently drops rows looks identical to a working one in manual testing.

Report: `app/build/reports/androidTests/connected/index.html`

---

## 4. Manual testing on a device

### 4.1 Requirements

- ARCore-certified Android device ([list](https://developers.google.com/ar/devices))
- Android 7.0+ (API 24)
- **A physical tape measure** — you're verifying honesty, so you need ground truth

```bash
./gradlew installDebug
```

### 4.2 The core test: are we actually right?

For each span below: measure with a physical tape, then with Tape. Record whether
the true value falls inside Tape's stated range.

| # | Target | ~Span | Expect |
|---|---|---|---|
| 1 | Laptop width, close up, bright room | 0.3 m | HIGH, tight tolerance |
| 2 | Doorway width | 0.8 m | HIGH |
| 3 | Sofa / table length | 2 m | HIGH–FAIR |
| 4 | Room wall, standing back | 5–6 m | FAIR, visibly wider tolerance |

**Pass criterion:** the physical value falls inside `shown ± tolerance` in at least
7 of 8 attempts. If the true value regularly falls *outside* the band, the engine is
overconfident — that is a bug, and a serious one.

**Also check:** the tolerance should *visibly widen* from test 1 to test 4. If it
looks constant, the feature is not working.

### 4.3 Degraded conditions — the refusal paths

| # | How to trigger | Expected behaviour |
|---|---|---|
| 5 | Dim the lights / cover part of the lens | Grade drops, hint says move somewhere brighter |
| 6 | Measure a blank white wall | Grade drops, hint mentions texture/pattern |
| 7 | Wave the phone around while measuring | Grade drops, hint says hold steadier |
| 8 | Place points, then cover the camera | Badge → "Can't verify", **Save disabled**, no stale number |
| 9 | Place points, then walk away so anchors drop | "A point lost tracking…", Save disabled |

**Critical:** in 8 and 9 the app must NOT keep displaying the last good tolerance.
A stale number presented as current is the exact failure this feature removes.

### 4.4 Persistence and sharing

| # | Step | Expected |
|---|---|---|
| 10 | Save a HIGH measurement → open Saved | Row shows value AND `±x.x cm` in the grade colour |
| 11 | Save a LOW measurement | Tolerance renders in the red/amber grade colour |
| 12 | Long-press a row → Share | Text includes the `±` figure, not a bare number |
| 13 | Change unit in Settings, reopen Saved | Both value and tolerance convert |
| 14 | Try to Save while "Can't verify" | Save button disabled |

### 4.5 Upgrade path (data safety)

| # | Step | Expected |
|---|---|---|
| 15 | Install the pre-branch build, save 2–3 measurements, then install this build over it | Old measurements still present |
| 16 | Inspect those old rows | Show "accuracy not recorded" — NOT an invented tolerance |

Step 16 is deliberate: we have no record of the conditions those were taken under,
so back-filling a plausible number would be the same lie in a new place.

---

## 5. Where the code lives

| Path | Role |
|---|---|
| `domain/accuracy/AccuracyEngine.kt` | The error model. Pure Kotlin, no Android imports — that's why it's JVM-testable. |
| `domain/model/AccuracyEstimate.kt` | Result type: grade, tolerance, relative, reason, formatters |
| `ui/screens/measure/MeasureViewModel.kt` | Captures placement metadata at tap; bridges ARCore enums to the engine |
| `ui/screens/measure/MeasureScreen.kt` | Confidence-Forward HUD; Save gating |
| `ui/screens/saved/SavedListScreen.kt` | Tolerance on saved rows + share text |
| `data/db/TapeDatabase.kt` | `MIGRATION_1_2` |
| `app/src/test/.../AccuracyEngineTest.kt` | Unit tests |
| `app/src/androidTest/.../MeasurementMigrationTest.kt` | Migration test |

### Design constraints — don't break these

1. **`AccuracyEngine` must not import ARCore or Android.** ARCore classes are
   native-backed and can't be instantiated in JVM tests. That's why `TrackingIssue`
   and `AnchorSurface` mirror ARCore's enums; `MeasureViewModel` does the bridging.
2. **Placement metadata is captured at tap time only.** `HitResult.distance` and the
   trackable type are not recoverable later.
3. **Never re-introduce a constant accuracy string.** If a number can't be derived
   from an observed signal, show nothing and explain why.
4. **Don't back-fill legacy rows with invented tolerances.**

---

## 6. Full verification sweep

```bash
./gradlew clean assembleDebug testDebugUnitTest lint

# with a device attached:
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tape.measure.data.db.MeasurementMigrationTest

# the old lie must not have returned:
grep -rn "±1 cm" app/src/main/java --include=*.kt | grep -v "^.*\*"   # expect: no hits
```

---

## 7. Known limitations (stated honestly)

- **Constants are literature-informed, not field-calibrated.** The 1% base and the
  range ramp come from published ARCore accuracy ranges, not from measurements taken
  on a bench with this code. Section 4.2 is how you'd falsify them. If the true value
  routinely lands outside the stated band, retune — and update
  `AccuracyEngineScenarioTest` expectations with it.
- **No per-device tiering.** A Pixel 9 Pro and a budget phone get the same base term.
  Real tiering needs a hardware capability table backed by field data we don't have;
  inventing one would be fabrication.
- **Depth API unused.** Devices with a ToF sensor could bound error better. Deferred.
- **Tolerance is not validated against ground truth in CI.** It can't be — it needs a
  physical tape and a real room. Hence section 4.2 is a human step.
