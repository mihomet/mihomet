# Mihomet — Agent Guide

> Brief & actionable reference for AI coding agents working on the Mihomet codebase.

## 1. Project Overview

**Mihomet** is a high-performance Android client for the **Mihomo** (`Clash.Meta` v1.19+) core.

- **Dual execution modes**:
  1. **Non-Root VPN**: Android `VpnService` (TUN interface + gVisor stack).
  2. **Root TProxy**: `su` root shell with `iptables` / `nftables` transparent proxying.
- **OS range**: minSdk **26** (Android 8.0) → targetSdk 36 (compileSdk 37).
- **Language**: Kotlin 2.1, Coroutines/Flow, kotlinx.serialization, Ktor 3.3 client.
- **UI**: Jetpack Compose (BOM 2026.06), **Material 3 Expressive**.
- **Localization**: strings live in resources — `values/strings.xml` (**EN**, default) and `values-ru/` (Russian).
  New UI text **must** go through `stringResource`, never hardcoded, and must be added to **both** locales.
  Converter warnings are localized at display time by `core/config/WarningLocalizer.kt` (RU/EN pattern list).
- **License**: GPL-3.0 (the bundled mihomo core is GPL — do not change the license).

## 2. Architecture & Directory Map

```text
app/src/main/kotlin/org/mihomet/app/
├── core/                        # Core engine & OS interop
│   ├── CoreConstants.kt         # SINGLE source of truth: ports, fwmarks, TUN, root paths, AUTO balancer
│   ├── CoreManager.kt           # libmihomo.so lifecycle, updates, temporary probe instance
│   ├── ServiceState.kt          # Global reactive state (isRunning, runningService: VPN | ROOT)
│   ├── DeepLinkParser.kt        # happ://, incy://, clash://, v2rayng://, sing-box://, share-target
│   ├── DeepLinkPolicy.kt        # fail-closed accept/reject decisions for external links (tested)
│   ├── AppError.kt              # typed AppErrorCode/AppException — no UI-language strings in core
│   ├── update/CoreUpdateVerifier.kt  # RSA-signed manifest → sha256 → size chain for core updates
│   ├── HappCrypto.kt            # Happ AES-128-GCM encrypted subscriptions
│   ├── HwidProvider.kt          # x-hwid device header for panel compatibility
│   ├── OemHints.kt              # OEM-specific firewall hints (MIUI battery killers etc.)
│   ├── config/                  # ConfigGenerator (runtime YAML), UniversalConfigConverter,
│   │                            #   ConversionOutcome (convertToMihomoYamlWithOutcome → warnings),
│   │                            #   WarningLocalizer (display-time RU/EN warning translation)
│   ├── dns/DnsPresets.kt        # DNS server presets + custom DNS setting
│   ├── firewall/                # FirewallManager + IptablesEngine / NftablesEngine
│   ├── network/NodePingTester   # Direct TCP/TLS/HTTP/ICMP latency (capped by Semaphore(8))
│   ├── root/                    # RootShellManager (pid-file daemon), RootTProxyService
│   └── vpn/                     # MihometVpnService (thin orchestrator) + TunBuilder,
│                                #   CoreProcessLauncher, CoreReadinessProbe, CoreWatchdog,
│                                #   VpnNotificationController, UnderlyingNetworkTracker
├── data/
│   ├── api/                     # MihomoApiClient (REST+WS, Closeable, Ktor 3), MihomoApiClientProvider
│   │                            #   (provider rebuilds the client when port/secret change)
│   ├── update/                  # In-app self-update: semver check, fail-closed verifier
│                                #   (version-info.json + signing-cert equality), PackageInstaller
│   ├── crypto/SecretsCipher.kt  # Keystore-backed encryption of sensitive settings + keyring
│   ├── db/                      # Room: AppDatabase, ProfileDao, ProfileEntity
│   ├── preferences/             # DataStore: SettingsRepository (generates random controller secret)
│   └── subs/                    # SubscriptionRepository (streamed download, hard byte cap),
│                                #   ProfileValidation (semantic checks + atomic writes),
│                                #   SubscriptionRefreshWorker (WorkManager auto-refresh)
├── di/AppModule.kt              # Koin: ViewModels, repositories, MihomoApiClientProvider
├── service/                     # MihometTileService (quick-settings tile), widget provider
└── ui/                          # Compose M3 Expressive
    ├── components/              # ExpressiveNavigationBar, badges, pulsing dot
    ├── navigation/              # NavGraph (shared-axis transitions), Screen.kt
    ├── screens/                 # dashboard, proxies, profiles, routing, settings, logs, connections
    └── theme/                   # Theme/Color/Shape/AppHaptics (light, dark, AMOLED)
```

Docs worth reading before touching a sensitive area: `README.md`, `TODO.md` (roadmap + panel
compatibility matrix), `CHANGELOG.md`, `docs/compatibility-research.md` (panel format traps &
fixtures), `SECURITY.md`, `CONTRIBUTING.md`.
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
5. **The bundled core is NOT stock MetaCubeX mihomo.** It is mihomo v1.19.33 patched for non-root TUN
   (stock core dies reading `/data/system/packages.xml`; patch idea: Tropa, GPL-3.0), built by the
   separate **`mihomet/core`** repo (docker golang + Android NDK, `-tags with_gvisor`,
   `ANDROID_PLATFORM=android-26` — targeting newer silently breaks Android 8–13) and published as raw
   `libmihomo-<abi>.so` at `github.com/mihomet/core/releases`. `downloadMihomoCore` (app/build.gradle.kts)
   fetches the pinned release, verifies sha256+size from `gradle/mihomo-core.properties` and installs to
   `app/src/main/jniLibs/`. Bump versions ONLY by updating that properties file, and only after verifying
   the release artifacts — the binary runs on-device, including as root. Local rebuild outputs land in
   `core-build/dist/` (gitignored); geo DBs track the rolling MetaCubeX/meta-rules-dat "latest".
6. **No disruptive snackbars for core start/stop** — state is shown via the hero button and badges.
7. **Converter losses are never silent**: use `convertToMihomoYamlWithOutcome` and surface
   `warnings` to the user; new protocols must be added to `parseUriToMihomoProxy` dispatch + tests.
8. **Cancellation hygiene**: services own `SupervisorJob` scopes and cancel them in `onDestroy`;
   per-node network tests are capped by semaphores.
9. **Localization**: strings live in `res/values/strings.xml` (English, default) and `res/values-ru/strings.xml`
   (Russian). All UI text must go through `stringResource` (or `getString` in services/ViewModels) — never
   hardcode user-facing text, and every new string **must be added to both locales**.
10. **Releases are cut only from GitHub-verified SIGNED tags** (`git tag -s -a vX.Y.Z`;
   release.yml fails without a verified signature — the git identity email must be the one
   registered on the GitHub account). Every release ships `version-info.json`
   ({versionName, versionCode}) — the next release's versionCode must be strictly greater.
11. **Subscription handling is defensive by design**: downloads are streamed with a hard byte cap
   (works without `Content-Length`), profile writes are semantically validated + atomic
   (`data/subs/`). Encrypted subscriptions (Happ `encrypt-tag`, AES-128-GCM) keep keys in the
   client-side keyring; keyring values are Keystore-encrypted (`SecretsCipher`). New panel quirks /
    share-link schemes go into `UniversalConfigConverter` / `DeepLinkParser` **with fixture tests**
    (`PanelsSubscriptionTest` et al. — see `docs/compatibility-research.md`).

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
./gradlew testDebugUnitTest       # 2. unit tests (51 classes / 500+ @Test, incl. strict suites)
./gradlew assembleDebug           # 3. debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease         # 4. release APK (unsigned without KEYSTORE_* env vars)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires **JDK 21** (CI pins it). If the system default JDK is newer (e.g. 27), set
`JAVA_HOME=/home/misha/.jdks/jdk-21` for every gradle invocation. The `preBuild`-dependent
`downloadMihomoCore` task installs the pinned
core `.so` into `app/src/main/jniLibs/` and geo databases into `.mihomo-core-cache/`
(after the first build, this is offline-friendly except geo refreshes).

## 6. Release / CI

- CI (`.github/workflows/ci.yml`, JDK 21): compile + tests + debug APK on every push/PR (gradle + core caches).
- Release (`.github/workflows/release.yml`): tag `v*` → signed APK via repo secrets
  (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) → GitHub Release.
  Bump `versionCode`/`versionName` in `app/build.gradle.kts` and update `CHANGELOG.md` in the same PR.
- **Core updates are a two-step flow**: cut a release in the `mihomet/core` repo first, then point
  `gradle/mihomo-core.properties` at the new tag (sha256+size verified at build time).
