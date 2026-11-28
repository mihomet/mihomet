# Unreleased source export, opt-in analytics and UI verification — 2026-11-28

This records the `Unreleased` changes on PR #3: conversion-source export, optional
Aptabase analytics, conversion-report corrections and interface follow-up. The
final JDK 21 host gate succeeded. After the last disclosure and test-clarity
revisions, GitHub Actions passed the host checks and full API 26/API 35
instrumentation jobs on commit `9bb5203981b9aeb8f6cf3303bb94c2950ff4ba23`. Each
emulator job reported 43 test executions, zero failures and one assumption skip
for the optional Xray harness. An earlier intermittent CoreProbe failure remains
unexplained. This is not an all-platform pass or verification of a
production-signed release.

The scope follows [CHANGELOG.md](../CHANGELOG.md#unreleased) and
[analytics.md](analytics.md). The existing
[release verification](verification-2026-11-27.md) and
[UI follow-up verification](verification-2026-11-27-ui-followup.md) were read for
context. Their 664/672 JVM counts, older Android runs, screenshots, wire cases and
VPN smoke results are historical evidence, not fresh checks of these changes.
The build configuration still identifies the app as 1.3.8 (14); these changes
remain `Unreleased`.

## Build and static checks

The final gate used JDK 21 and two Gradle workers:

```sh
JAVA_HOME=/home/misha/.jdks/jdk-21 ./gradlew \
  compileDebugKotlin testDebugUnitTest assembleDebug assembleRelease \
  --no-daemon --max-workers=2
```

The original broad gate (including instrumentation APK assembly, detekt and lint)
completed successfully in 7m57s with 35 tasks executed and 103 up-to-date. After
the retry-chain fix, the required compile, full unit-test, debug APK and release
APK tasks completed successfully in 4m40s with 21 tasks executed and 77 up-to-date.
Test counts below come from `app/build/test-results/testDebugUnitTest/TEST-*.xml`; lint details
come from the generated reports.

| Check | Recorded result |
|---|---|
| Broad Gradle gate | `BUILD SUCCESSFUL in 7m57s`; compile, unit tests, debug/instrumentation APK assembly, release assembly, detekt and lint |
| Post-fix Gradle gate | `BUILD SUCCESSFUL in 4m40s`; compile, full unit tests, debug APK and release APK |
| JVM tests | 701 tests across 72 XML suites; 0 failures, 0 errors, 0 skipped |
| Debug and instrumentation APK assembly | Succeeded in the broad gate |
| Release assembly, including R8 | Succeeded; local release APKs are unsigned |
| App detekt | Succeeded in the broad gate; the root `detekt` task itself is `NO-SOURCE` |
| Debug lint | Succeeded in the broad gate with the existing baseline; see counts below |
| EN/RU resources | 482 named entries in each locale; matching keys and format placeholders; no duplicate names |
| Workflow YAML | All four workflow files parsed successfully |

The final lint report contains **0 errors, 43 warnings and 1 hint**, with
**15 errors, 64 warnings and 1 hint filtered by `lint-baseline.xml`**. This is a
successful baselined lint gate, not a warning-free or baseline-free result.
Neither lint nor detekt baseline was expanded; no baseline file appears in the
current Git diff.

The resource cross-check parsed `app/src/main/res/values/strings.xml` and
`app/src/main/res/values-ru/strings.xml`. Placeholder comparison accounts for
Russian-specific plural quantities rather than comparing the number of plural
branches. YAML parsing covered `.github/workflows/ci.yml`, `deps-updates.yml`,
`release.yml` and `signed-test-apk.yml`; parsing does not establish that the
workflows have run successfully on GitHub.

JaCoCo was not part of this final gate. The EN/RU analytics disclosure was
updated after the previous full API 26/API 35 instrumentation runs; the final
host gate recompiled the app and instrumentation APKs, but no emulator was
connected for another device run after that wording-only change.

## Conversion-source export and restoration

The current import/manual-refresh warning or error can expose the available
conversion input through a separate, confirmed file-export action. The input
includes fields not imported into the resulting configuration. Normal saved or
shared diagnostic reports continue to exclude it. This additional diagnostic
copy is held in memory and cannot be recovered from a persisted conversion
report. For Happ key/decryption failures, the available source is the encrypted
response body, not a claim that plaintext was recovered.

`DiagnosticExportActions.kt` restores only the pending export kind through
`rememberSaveable`; the source and its builder are not saved in instance state.
The source report is built after a successful file-selection result. If the
confirmed source handler has been lost through state restoration, export
reports an error and leaves the selected file empty, even if the current UI has
a source available. The user can confirm a fresh export. This prevents a
parallel profile B operation from replacing the profile A source confirmed
before recreation; no source identifier or content is put into saved state.

The six `ConversionSourceExportInstrumentedTest` cases cover ordinary save
excluding a synthetic source token, confirmation/cancellation and exact
source-report contents, Russian buttons at 320 dp with 2× font scale, and these
**three restoration regressions**:

- `restoredSourceExportRequiresConfirmationAgainEvenWhenSourceIsStillAvailable`:
  saved-state restoration does not relaunch `CreateDocument` or eagerly build
  the source; completing the original selection shows an error and leaves the
  file empty. A new confirmation and file selection writes the exact source
  report once.
- `restoredSourceExportCannotSubstituteAnotherProfilesSource`:
  after confirming source A, restoration provides source B in the same UI slot;
  completing the original selection reports an error, leaves the file empty,
  and never invokes either source builder.
- `missingSourceAfterRestorationShowsErrorAndLeavesFileEmpty`:
  after source loss, restoration keeps the pending source-export choice;
  completing selection shows an error and leaves the selected test file empty.

These restoration tests use Compose `StateRestorationTester` and a recording
activity-result registry. They verify saved-state and result handling, without
establishing end-to-end behavior for every document provider or process-death
scenario.

The Russian layout test initially reported width overflow on API 35 even though
the captured label was visible. Extra measurement recorded a 545 px placed
text width, a 608 px semantic paragraph, and line bounds of 32–576 px in that
wider paragraph. The plain-string Compose semantic helper rebuilds its
`MultiParagraph` using incoming constraints; these can differ from the
paragraph's measured width. The test now rebuilds from the returned font/text
intrinsics at the placed width and checks overflow against the original placed
size, including height and max-line overflow. It does not ignore overflow or
allow an arbitrary pixel tolerance. The actual controls retain centered labels
and 12 dp horizontal/vertical content padding.

Before the final source-identity restoration fix, the isolated API 35
source-export class passed its then-five cases after the layout-test correction,
in 38.809 s:
`/tmp/mihomet-followup-layout-fix-source-export-api35-20261128.log`.
The associated screenshot is
`/tmp/mihomet-followup-layout-fix-source-export-api35-20261128.png`.
After the source-identity restoration fix, all six source-export cases passed
on API 35 in 48.421 s:
`/tmp/mihomet-followup-final-source-identity-api35-20261128.log`.
Its screenshot is
`/tmp/mihomet-followup-final-source-identity-api35-20261128.png`.
The diagnostic failure showing the differing widths is recorded in
`/tmp/mihomet-followup-layout-measurements-api35-retry-20261128.log`.
Implementation reference for the semantic reconstruction:
[AndroidX ParagraphLayoutCache](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/modifiers/ParagraphLayoutCache.kt).
The debug lint dependency model resolves Compose foundation/ui-text to 1.12.1.
The cached foundation 1.12.1 AAR was also inspected with JDK 21 `javap`: its
`slowCreateTextLayoutResultOrNull` builds the semantic paragraph from
`prevConstraints` and returns it alongside the separately stored `layoutSize`.
The bytecode evidence is
`/tmp/mihomet-compose-foundation-1.12.1-paragraph-bytecode.txt`.

Additional API 26 cases passed for retaining encrypted Happ responses when a key
is missing and discarding earlier User-Agent response bodies and warnings when a
later fallback exceeds the size cap. JVM results also cover source/context retention
in typed errors and download/refresh results, ordinary diagnostic redaction and
explicit source inclusion. Relevant XML suites are `AppExceptionTest` (5),
`SubscriptionPayloadTest` (3) and `ConversionDiagnosticTest` (8), all passing.

Failure diagnostics now take their source, report and warnings from the failed
operation itself. Three retry-chain JVM regressions verify that a selected prior
conversion failure keeps its own source after a network or generic failure, and
that a selected current failure keeps its own context. Together with the profile
identity regressions, this prevents attaching another attempt's payload or
another profile's source after overlapping imports or refreshes. The current
JDK 21 results record 701 tests across 72 suites, with 0 failures, errors or
skips.

Selected Xray/Happ configuration rules are now counted separately from rules in
alternative configurations that are not merged. The 37 passing
`XrayConversionRegressionTest` cases include this distinction and backward
compatibility for older reports without the alternative-rule count.

Implementation and test references are
`app/src/main/kotlin/org/mihomet/app/ui/components/DiagnosticExportActions.kt`,
`ui/screens/profiles/ConversionDiagnostic.kt` and `ConversionReportSheet.kt`
(relative to the same Kotlin package root), and
`app/src/androidTest/kotlin/org/mihomet/app/ConversionSourceExportInstrumentedTest.kt`.
The payloads checked here are synthetic; no private subscription or user
diagnostic attachment was opened.

## Opt-in Aptabase analytics and APK contents

[analytics.md](analytics.md) describes the Unreleased integration with Aptabase
Kotlin **0.1.0**. It is disabled by default. Initialization requires both a
supported cloud key embedded at build time and user opt-in. A missing or
structurally invalid key hides the settings switch and prevents initialization.
Accepted trimmed keys match `A-(US|EU)-[A-Za-z0-9_]+`; self-hosted keys are not
supported. Format validation does not verify a registered Aptabase app.

The optional `APTABASE_APP_KEY` is wired into the release and signed-test-APK
workflows. The local debug build has no key configured, so its analytics setting
is hidden. No real Aptabase app key/account was available for verification.
**Live delivery, dashboard ingestion and real-account behavior were not tested.**

The tracker hands off only `app_started` (at most once per process, with no
custom properties) and `conversion_completed`. Conversion custom properties
are bounded: a known `format` or `unknown`, `success` as `success`/`failure`, and
`warning_count` clamped to 0–100. Conversion validity is measured before profile
persistence. Raw source, subscription URLs, configurations, credentials and
error text are excluded from the app's event payload.

The disclosure also covers SDK metadata, including session/timestamp,
OS/device/language and app/build/SDK versions. The current Aptabase privacy
policy and DPA also describe transient IP/User-Agent processing, stored
country/region and event-data retention up to five years; the in-app EN/RU
disclosure and `docs/analytics.md` reflect those points. Disabling propagates
asynchronously through DataStore. Once observed, it blocks new SDK handoffs and
cancels pending tracker jobs; it cannot recall an event already handed to this
SDK. Delayed events from an older consent period remain rejected after
re-enabling.

The full JDK 21 gate included **10 `AnalyticsEventPayloadTest` cases**, covering
supported key validation, bounded fields and format normalization, consent-period
isolation, SDK-handoff synchronization, initialization retry and the once-per-process
launch gate. These are local contract tests, not evidence of live analytics delivery.

A startup review found that the analytics settings collector could read settings
before `SecretsCipher.keyGate` had been assigned. Resolving a controller secret
with a missing Keystore alias could then bypass the existing data-loss guard.
`MihometApplication` now installs the gate before starting the tracker. A caller
that races the background encrypted-data probe waits through the installed gate
and fails closed on timeout. Existing `SecretsCipherGateTest` cases cover the
key guard itself; there is no new test asserting Application startup ordering.

The unused runtime `androidx.test:monitor` dependency declared by Aptabase is
explicitly excluded in `app/build.gradle.kts`. The final dependency
check reports no monitor in debug/release runtime dependencies and
**monitor 1.8.0 for androidTest**. A read-only DEX cross-check of all three debug
and three unsigned release ABI splits found none of the monitor descriptors
`androidx.test.runner.MonitoringInstrumentation`,
`androidx.test.runner.lifecycle.ActivityLifecycleMonitor` or
`androidx.test.platform.app.InstrumentationRegistry`. The instrumentation APK
contains these descriptors. The final APK checks used the already-built artifacts.

Local APK locations:

- Debug: `app/build/outputs/apk/debug/app-{arm64-v8a,armeabi-v7a,x86_64}-debug.apk`.
- Release: `app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a,x86_64}-release-unsigned.apk`.
- Instrumentation: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

`apksigner verify --print-certs` verified all three debug splits with the
**Android Debug** certificate (`C=US, O=Android, CN=Android Debug`), SHA-256
`c66de7c61ba7bf976369b16d0d920635e2d241ef9299ed0958838500049559ce`.
All three local production release-variant APKs are **unsigned**; signature
verification fails as expected. Successful release assembly is not verification
of a production signature or a production-signed installation.

## Android results and UI coverage

| Run | Passed | Failed | Assumption-skipped | Evidence |
|---|---:|---:|---:|---|
| API 26 initial full suite, 40 tests | 38 | 1 | 1 | `/tmp/mihomet-followup-final-api26-20261128.log` |
| API 26 three isolated CoreProbe repeats | 3/3 | 0 | 0 | `/tmp/mihomet-probe-repeat-{1,2,3}-20261128.log` |
| API 26 full rerun, 40 tests, 85.832 s | 39 | 0 | 1 | `/tmp/mihomet-followup-final-api26-rerun-20261128.log` |
| API 26 current full suite, 41 tests, 92.123 s | 40 | 0 | 1 | `/tmp/mihomet-followup-final-current-api26-20261128-full.log` |
| API 35 final full suite, 41 tests, 151.958 s | 40 | 0 | 1 | `/tmp/mihomet-followup-final-current-api35-20261128-full.log` |
| API 26 focused subscription fallback suite after CI fix, 8 tests | 8 | 0 | 0 | local JDK 21 run, `SubscriptionUaFallbackInstrumentedTest` |
| API 35 focused CoreProbe + subscription fallback suites, 9 tests | 9 | 0 | 0 | local JDK 21 run, `CoreProbeInstrumentedTest` + `SubscriptionUaFallbackInstrumentedTest` |
| GitHub Actions API 26 full job | 43 executions reported | 0 | 1 | [CI run 46118256386](https://github.com/mihomet/mihomet/actions/runs/46118256386), job 140896810569; optional Xray harness assumption-skipped |
| GitHub Actions API 35 full job | 43 executions reported | 0 | 1 | [CI run 46118256386](https://github.com/mihomet/mihomet/actions/runs/46118256386), job 140896810506; optional Xray harness assumption-skipped |
| GitHub Actions host checks | Passed | 0 | 0 | [CI run 46118256386](https://github.com/mihomet/mihomet/actions/runs/46118256386), job 140896810351; compile, JVM tests, lint, detekt, debug APK, core hash/version checks |

The initial failure was
`CoreProbeInstrumentedTest.temporaryCoreCanReachLoopbackWithoutRootOrAnActiveVpn`,
with `java.lang.AssertionError: Unprivileged probe failed: null`. The initial
suite took 86.832 s. The same probe then passed three isolated repeats
(2.159 s, 1.034 s and 0.924 s) and passed in the full rerun, which ended with
`OK (40 tests)`. These repeats used the same final
APK/API 26; production/test code was not changed between them.

**The cause of the initial negative probe result is not established.** The same
probe later passed three isolated repeats, the prior full rerun and the current
full API 26 suite. It also passed in the focused API 35 rerun after CI reported
an intermittent failure. These successful reruns show that the failure did not
recur; they do not explain the original result.

The single skip in each current API 26/API 35 full suite is the optional
`XrayLoopbackInstrumentedTest.convertedClientsReachTheLocalHttpServerThroughXray`:
the local Xray harness was not supplied. Runner `OK (41 tests)` therefore means
40 passed plus one assumption skip, not 41 passing connections. The older API 26
rerun recorded `OK (40 tests)` and 39 passes plus that same skip.

The available connected-test XML at
`app/build/outputs/androidTest-results/connected/debug/TEST-matrix_api26(AVD) - 8.0.0-_app-.xml`
contains an older partial run of three import-flow cases. It is not evidence for
the current 41-test runs; their individual statuses and totals come from the
instrumentation logs listed above. The isolated probe repeats are additional
runs, not additions to the suite cases.

API 26 UI instrumentation passed compact journal layouts at default, large and
maximum font scale; selected connecting accent; return-to-dashboard transition
direction; cumulative log counts and preservation of reading position; failed
import diagnostics; and source-export controls including Russian 320 dp / 2×
font-scale labels. `NavigationDirectionTest` also passed on the JVM. These checks
support the relevant Unreleased UI behavior. The current API 26 screenshots were visually inspected at 320 dp with font
scales 1.0, 1.5 and 2.0, including the Russian source-export dialog:
`/tmp/mihomet-followup-final-current-api26-20261128-conversion-export-ru-2x.png`
and `/tmp/mihomet-followup-final-current-api26-20261128-compact-{1.0,1.5,2.0}.png`.
This does not repeat the earlier full light/dark/AMOLED visual sweep.

The final API 35 full suite completed in 151.958 s with 41 distinct cases:
40 passed, no failures and the optional Xray harness case assumption-skipped.
The crash buffer captured after this run was empty:
`/tmp/mihomet-followup-final-current-api35-20261128-crash.log`.
The API 35 source-export and compact journal screenshots were visually inspected:

- `/tmp/mihomet-followup-final-current-api35-20261128-conversion-export-ru-2x.png`;
- `/tmp/mihomet-followup-final-current-api35-20261128-compact-1.0.png`;
- `/tmp/mihomet-followup-final-current-api35-20261128-compact-1.5.png`;
- `/tmp/mihomet-followup-final-current-api35-20261128-compact-2.0.png`.

The final suite includes all six source-export cases and the journal,
connecting-accent, navigation-direction, log-counter and import-flow checks.
The x86_64 debug app and instrumentation APK hashes were captured in
`/tmp/mihomet-followup-final-device-apk-hashes-20261128.txt` and matched after
the corresponding debug assembly tasks in the final host gate:

```text
app-x86_64-debug.apk: bb82a29a0155abcb17bb09942f99854eaeb9a29131945635c379134a2756a13a
app-debug-androidTest.apk: 9628563f11f6533dedacc921b3fc8ef4047854796e2ba01b6bc4ae6c250bfcb3
```

Russian labels remained readable at 320 dp with font scales 1.0, 1.5 and 2.0.
These component captures do not establish a new full-screen theme sweep.

Earlier API 35 attempts stopped with `Process crashed` before running tests:
`/tmp/mihomet-followup-final-api35-20261128.log` and
`/tmp/mihomet-followup-layout-measurements-api35-20261128.log`.
The startup log
`/tmp/mihomet-followup-layout-diagnostics-startup-logcat-20261128.log`
contains an app startup ANR and a SystemUI ANR during cold boot. Later runners
waited for the BOOT_COMPLETED broadcast backlog before instrumentation and the
final run passed. This sequence does not establish the root cause of those
startup failures. A subsequent diagnostic full run had 38 passed, one skipped
and the source-button semantic-layout assertion failure described above:
`/tmp/mihomet-followup-final-api35-diagnostic-20261128.log` (173.686 s).

## Remaining limits and publication status

- Physical ARM devices, root/TProxy operation and a real VPN tunnel were not
  verified for these current changes. Building ARM splits, manifest tests,
  core configuration checks and a temporary non-root loopback probe do not
  establish those outcomes.
- The optional Xray wire harness was not run in these full suites. Earlier wire
  results and VPN smoke in the 2026-11-27 release report are not new results for
  this working tree.
- The `APTABASE_APP_KEY` GitHub Actions secret is configured; its value was not read.
  No live Aptabase ingestion/dashboard or production-signed APK was tested.
- [Promo drafts](promo/2026-11-28.md) describe v1.3.8, not these Unreleased
  additions. GitHub Discussion #2 was published; the other platform copy remains
  draft material. PR #3 is still a draft; analytics/source-export work has not been
  merged or released.
- Evidence uses repository documentation/code, synthetic test data, existing
  build/test artifacts and the explicitly listed local logs. Private sources
  and the user's diagnostics attachment were not accessed.

PR #3 is open as a draft, and its changes are committed and pushed on
`codex/diagnostics-analytics`; they have not been merged or tagged. Local `/tmp`
logs and build outputs are evidence on this workstation, not tracked release
artifacts; later runs may replace build reports.

## Follow-up audit fixes

A code review found that the ordinary report-save callback did not write its
text to the selected file. The callback now writes the sanitized report through
the same streaming writer used by source export. The disclosure and release notes
were also corrected: a response that exceeds the hard size limit before it is
fully read has no exportable body, and earlier User-Agent fallback bodies and
warnings are not attached to that failure.

Focused verification after these fixes:

- `ConversionSourceExportInstrumentedTest`: 6 tests passed on the Android 15
  (`Pixel_8_API_35`) emulator, including `safeSaveWritesReportWithoutSourcePayload`.
- `AnalyticsEventPayloadTest`: passed on JDK 21 after adding the stale-consent
  regression case; an event rejected at handoff does not consume a process event
  slot.
- `git diff --check`: passed.

These focused reruns do not replace the earlier full-suite report above.
