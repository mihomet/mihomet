# Contributing to Mihomet

Thanks for your interest! Mihomet is an Android client for the
[mihomo](https://github.com/MetaCubeX/mihomo) core, licensed under **GPL-3.0**.
By contributing you agree your work is licensed under the same terms.

## Development setup

- **JDK 21** and the **Android SDK (API 35, Build-Tools 35)**.
- The mihomo core binary is **not stored in git** — it is downloaded on the
  first build (see `gradle/mihomo-core.properties` for the pinned version and
  checksums). The `.mihomo-core-cache/` directory makes subsequent builds
  offline-friendly.

```bash
./gradlew compileDebugKotlin   # fast compile check
./gradlew testDebugUnitTest    # unit test suite
./gradlew assembleDebug        # debug APK
```

## Ground rules

1. **User-facing strings go through resources.** All UI copy lives in
   `app/src/main/res/values/strings.xml` (English) and
   `values-ru/strings.xml` (Russian). Do not hardcode text in Composables or
   ViewModels.
2. **No new hardcodes for core integration constants** — ports, fwmarks, TUN
   settings and root paths live in `CoreConstants.kt`.
3. **Tests for pure logic.** Subscription parsing, config generation and
   helpers must come with unit tests.
4. **Both connection modes must keep working** (non-root VPN and root TProxy).
5. Run the checks above before opening a PR; CI will run them again.

## Releasing

Push a tag `v<version>`; the release workflow builds and signs the APK.
Signing keys are provided via repository secrets (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and never committed.
