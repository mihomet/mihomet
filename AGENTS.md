# Mihomet — Agent Guide

> Brief & actionable reference for AI coding agents working on the Mihomet codebase.

## 1. Project Overview

**Mihomet** is a high-performance Android client for the **Mihomo** (`Clash.Meta` v1.19+) core.

- **Dual execution modes**:
  1. **Non-Root VPN**: Android `VpnService` (TUN interface + gVisor stack).
  2. **Root TProxy**: `su` root shell with `iptables` / `nftables` transparent proxying.
- **OS range**: minSdk **26** (Android 8.0) → targetSdk 35.
- **Language**: Kotlin 2.1, Coroutines/Flow, kotlinx.serialization.
- **UI**: Jetpack Compose (BOM 2024.12.01), **Material 3 Expressive**.
- **Localization**: strings live in resources — `values/strings.xml` (**EN**, default) and `values-ru/` (Russian).
  Historically the UI was Russian-only; strings are being extracted (see TODO.md Phase 3). New UI text **must** go through
  `stringResource`, never hardcoded, and must be added to **both** locales.
- **License**: GPL-3.0 (the bundled mihomo core is GPL — do not change the license).

## 2. Architecture & Directory Map

```text
app/src/main/kotlin/org/mihomet/app/
├── core/                        # Core engine & OS interop
│   ├── CoreConstants.kt         # SINGLE source of truth: ports, fwmarks, TUN, root paths
│   ├── CoreManager.kt           # libmihomo.so lifecycle, updates, temporary probe instance
│   ├── ServiceState.kt          # Global reactive state (isRunning, runningService: VPN | ROOT)
│   ├── config/                  # ConfigGenerator (runtime YAML), UniversalConfigConverter,
│   │                            #   ConversionOutcome (convertToMihomoYamlWithOutcome → warnings)
│   ├── firewall/                # FirewallManager + IptablesEngine / NftablesEngine
│   ├── network/NodePingTester   # Direct TCP/TLS/HTTP/ICMP latency (capped by Semaphore(8))
│   ├── root/                    # RootShellManager (pid-file daemon), RootTProxyService
│   └── vpn/MihometVpnService    # VpnService, TUN fd, notification (+optional live speed)
├── data/
│   ├── api/                     # MihomoApiClient (REST+WS, auto-reconnect), MihomoApiClientProvider
│   │                            #   (provider rebuilds the client when port/secret change)
│   ├── db/                      # Room: AppDatabase, ProfileDao, ProfileEntity
│   └── preferences/             # DataStore: SettingsRepository (generates random controller secret)
├── di/AppModule.kt              # Koin: ViewModels, repositories, MihomoApiClientProvider
├── service/MihometTileService   # Quick-settings tile (SupervisorJob scope)
└── ui/                          # Compose M3 Expressive
    ├── components/              # ExpressiveNavigationBar, badges, pulsing dot
    ├── navigation/              # NavGraph (shared-axis transitions), Screen.kt
    ├── screens/                 # dashboard, proxies, profiles, routing, settings, logs, connections
    └── theme/                   # Theme/Color/Shape/AppHaptics (light, dark, AMOLED)
```

## 3. Key Facts & Invariants

1. **No new hardcodes for core integration.** Ports, fwmarks, TUN addresses, MTU, root paths live in
   `CoreConstants.kt`. The controller secret is generated randomly at first launch (`SettingsRepository`);
   never reintroduce a literal default.
2. **API access goes through `MihomoApiClientProvider.current()`** — never construct `MihomoApiClient`
   with hardcoded host/port/secret in ViewModels.
3. **Never break non-root mode** (must run cleanly without root) **and never break root mode**
   (TProxy rules and root fallback).
4. **Security defaults**: `allow-lan: false`, DNS/`bind-address` on `127.0.0.1`, `allowBackup="false"`.
   Do not relax them without an explicit user-visible toggle.
5. **The mihomo binary is not in git.** `downloadMihomoCore` (app/build.gradle.kts) downloads the pinned
   release + verifies sha256 from `gradle/mihomo-core.properties`. Bump versions ONLY by updating that
   file (checksums from the official MetaCubeX release).
6. **No disruptive snackbars for core start/stop** — state is shown via the hero button and badges.
7. **Converter losses are never silent**: use `convertToMihomoYamlWithOutcome` and surface
   `warnings` to the user; new protocols must be added to `parseUriToMihomoProxy` dispatch + tests.
8. **Cancellation hygiene**: services own `SupervisorJob` scopes and cancel them in `onDestroy`;
   per-node network tests are capped by semaphores.
9. **Localization**: strings live in `res/values/strings.xml` (English, default) and `res/values-ru/strings.xml`
   (Russian). All UI text must go through `stringResource` (or `getString` in services/ViewModels) — never
   hardcode user-facing text, and every new string **must be added to both locales**.

## 4. UI Guidelines (Material 3 Expressive)

- Shapes: `ExpressiveShapes` (`Hero` 38dp, `Large` 26dp, `Small` 12dp, `Pill` = CircleShape).
- Inactive surfaces: `MaterialTheme.colorScheme.surfaceContainerHighest` + `outlineVariant` border.
- AMOLED: `background = #000000`, neutral graphite containers — no color tint.
- Navigation transitions: directional shared-axis slide + fade (`FastOutSlowInEasing`).
- Brand colors: primary violet `#8B5CF6` (light variant `#A78BFA`), accents teal `#2DD4BF`, pink `#F472B6`.
  Logo variants live in `design/logos/` (`variant-1-m-network` is implemented).

## 5. Verification & Build Commands

Always run these before finishing a task:

```bash
./gradlew compileDebugKotlin      # 1. compile check
./gradlew testDebugUnitTest       # 2. unit tests (40+)
./gradlew assembleDebug           # 3. debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease         # 4. release APK (unsigned without KEYSTORE_* env vars)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First build (or CI) downloads the mihomo core + geo databases into `.mihomo-core-cache/`
(after that, builds are offline-friendly).

## 6. Release / CI

- CI (`.github/workflows/ci.yml`): compile + tests + debug APK on every push/PR.
- Release (`.github/workflows/release.yml`): tag `v*` → signed APK via repo secrets
  (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) → GitHub Release.
