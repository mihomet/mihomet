# UI and diagnostics follow-up verification — 2026-11-27

These checks cover the interface, log-buffer and conversion-diagnostic changes
prepared after the v1.3.8 release.

## Build and static checks

The final local gate ran on JDK 21:

```sh
JAVA_HOME=/home/misha/.jdks/jdk-21 ./gradlew \
  compileDebugKotlin testDebugUnitTest detekt assembleDebug assembleRelease \
  lintDebug jacocoUnitTestReport --max-workers=2
```

- The build completed successfully; all 672 JVM unit tests passed.
- Detekt passed. Debug lint reported 42 existing warnings and one hint; the
  lint and detekt baselines were not expanded.
- English and Russian resources include the same new log-counter and
  conversion-diagnostic strings.
- `git diff --check` passed.

The final local gate log is `/tmp/mihomet-followup-final-debug-gates.log`.

## Android instrumentation and UI

- API 26 instrumentation passed all 32 mandatory tests. The optional Xray
  loopback test was skipped because no Xray harness was supplied.
- API 35 instrumentation completed with `OK (33 tests)` in
  `/tmp/mihomet-followup-device35.log`.
- The follow-up UI tests cover compact layouts, readable labels, navigation
  direction and conversion-report actions. API 35 screenshots were inspected
  for the dashboard and Russian log screen.

## Conversion report privacy

The exported failure report was inspected at
`/tmp/mihomet-followup-exported-diagnostic.txt`. It contains stable issue codes
and safe conversion metadata; subscription URLs, credentials and raw parser
excerpts are omitted. Unit tests also check that credential-like source values
do not appear in exported reports.

Device validation used Android emulators. Physical-device rendering and
manufacturer-specific behavior were not covered by this follow-up.
