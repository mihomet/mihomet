# Contributing to Mihomet

Thanks for your interest! Mihomet is an Android client for the
[mihomo](https://github.com/MetaCubeX/mihomo) core, licensed under **GPL-3.0**.
By contributing you agree your work is licensed under the same terms.

## Development setup

- **JDK 21** and the **Android SDK (platform 37)**. The wrapper pins Gradle 9.3.1;
  AGP 9.1.0 uses built-in Kotlin 2.3.20. Do not reapply `kotlin-android`.
- On memory-constrained hosts, keep the configured two-worker limit and run
  emulators separately from release/R8 builds.
- The mihomo core binary is **not stored in git** — it is downloaded on the
  first build (see `gradle/mihomo-core.properties` for the pinned version and
  checksums). The `.mihomo-core-cache/` directory makes subsequent builds
  offline-friendly.

```bash
./gradlew compileDebugKotlin   # fast compile check
./gradlew testDebugUnitTest    # unit test suite
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # unsigned without KEYSTORE_* environment variables
./gradlew lintDebug detekt     # existing baselines must not conceal new issues
./gradlew assembleDebugAndroidTest # device tests, including bundled-core validation
```

For local wire compatibility checks, install the debug and androidTest APKs in a
booted emulator and run `python3 scripts/test-xray-loopback.py --serial
emulator-5554 --adb /path/to/adb --xray /path/to/xray`. This starts an isolated
loopback-only Xray instance and temporary HTTP/TLS servers, then checks converted
clients through the bundled Android core. It never uses your active Xray
configuration or contacts a public proxy. The optional device test is skipped
when this harness has not supplied its ephemeral fixture.

The [2026-11-27 verification record](docs/verification-2026-11-27.md) describes
the tested device matrix, release checks and remaining portability limits.

## Ground rules

1. **User-facing strings go through resources.** All UI copy lives in
   `app/src/main/res/values/strings.xml` (English) and
   `values-ru/strings.xml` (Russian). Do not hardcode text in Composables or
   ViewModels.
2. **No new hardcodes for core integration constants** — ports, fwmarks, TUN
   settings and root paths live in `CoreConstants.kt`.
3. **Tests for pure logic.** Subscription parsing, config generation and
   helpers must come with unit tests. Xray/Happ changes also need sanitized
   fixtures and a check against the pinned core; do not equate YAML shape with
   wire compatibility. Record public fixture provenance and hashes.
4. **Both connection modes must keep working** (non-root VPN and root TProxy).
5. Run the checks above before opening a PR; CI will run them again.

## Releasing

Push a tag `v<version>`; the release workflow builds and signs the APK.
Signing keys are provided via repository secrets (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and never committed.
