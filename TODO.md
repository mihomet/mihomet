# Mihomet — Project Roadmap & TODO

## 🎯 Active Milestone: Full Panel Compatibility for the Subscription Converter

**Objective**: Guarantee 100% accurate subscription fetching and format conversion for all popular VPN panels by testing against real Docker deployments and recorded fixtures.

### Supported / target panels matrix

| Panel | Status | Notes |
|---|---|---|
| Generic (Base64 URI list, Clash YAML, sing-box JSON) | ✅ tested | `UniversalConfigConverter`, fixture-based |
| Share-links (vless/vmess/trojan/ss/hysteria2/hysteria/tuic) | ✅ tested | incl. IPv6 hosts, SS2022 plain userinfo |
| Marzban | ✅ tested | fixture from live docker bench; VLESS+Reality, VMess+WS, SS, Trojan |
| Marzneshin | ✅ tested | fixture from live docker bench; clash + clashmeta + singbox + links |
| 3x-ui | ✅ tested | fixture; SS2022 plain-userinfo URI gap found & fixed |
| Remnawave | ✅ tested | fixture; default/clash/mihomo/stash/singbox/xray-json outputs |
| S-UI | ✅ tested | fixture; base64url SS2022 SIP002 form + singbox + clash |
| Hiddify Panel | ✅ tested | fixture; 96 links incl. ssh/snell/wg, expiry pseudo-link dropped with warning |
| Sub-Store | ✅ tested | fixture (mihomo/sing-box/v2ray targets); legacy `target=Clash` drops nodes — documented |
| subconverter | ⏸ skipped | legacy tool, rarely used by RU services in 2026; re-open on demand |
| behavior-заголовки (subscription-autoconnect / per-app-proxy-mode+list / tun-mode) | Happ zero-touch | ✅ применяются при импорте/обновлении |
| keyring-UI для crypto-ключей | Настройки → Продвинутые | ✅ |
| Виджет рабочего стола + тумблер | ✅ MihometWidgetProvider (v1.1.1) |
| Экспорт/шеринг логов + память ядра на дашборде | ✅ (v1.1.1) |
| Battery-промпт / allowBypass / HTTP-proxy opt-in | ✅ (v1.1.1) |
| Автостарт на загрузке (последний успешный режим) | ✅ BootReceiver + настройка |
| TUN-настройки (stack/MTU/bypass-LAN по CMFA-паттерну) | ✅ Настройки → Продвинутые |
| QR-код подписки + стриминг-лимит 20 МБ | ✅ zxing + поте чтение |
| Deep-link импорт (happ/incy/clash/v2rayng/sing-box) | ✅ implemented | `core/DeepLinkParser.kt`, live-tested on emulator |
| Управление по ссылкам (incy://connect|toggle, mihomet://) | ✅ implemented | MainActivity.handleControlLink |
| Автообновление подписок (profile-update-interval) | ✅ implemented | WorkManager 6h, Room v3 |

### Phase 1: Docker test bench — ✅ done
- [x] **1.1–1.6**: all panels deployed live in `/mnt/Data/mihomet-panel-bench/` (kept out of the repo):
      3x-ui, Marzban, Marzneshin, Remnawave (full stack + node), S-UI, Hiddify (bridge profile),
      Sub-Store. REPORT.md per panel with endpoints/credentials; containers left running.

### Phase 2: Converter integration tests
- [x] **2.1. Fixture-based unit tests**: `PanelsSubscriptionTest` scans `app/src/test/resources/panels/<panel>/`
      (real payloads captured from the live docker bench) and asserts every node survives conversion.
      Any new fixture is picked up automatically.
- [x] **2.2. Base64 resilience**: unpadded/URL-safe/multi-line covered by ConverterCombinationsTest.
- [x] **2.3. `Subscription-Userinfo` support**: parsed on add/update, quota bar + expiry date (colored by remaining days) in profile cards. Room 1→2 migration written without data loss.
- [x] **2.4. Header retention**: custom headers (incl. `x-hwid`, custom `User-Agent`) kept in auto-refresh via SubscriptionRepository; `APP_USER_AGENT` dynamically derived from `BuildConfig.VERSION_NAME`.
- [x] **2.5. Group alignment**: urltest/selector preserved (Sing-Box tests), fallback/load-balance passthrough (CombinationTest), panel fixtures assert groups.

### Phase 3: Localization RU/EN — ✅ done
- [x] ~215 hardcoded strings extracted into `res/values/strings.xml` (EN default) and `values-ru/strings.xml` (235 entries each).
- [x] ViewModel statuses emit resource ids with format args; plurals for import counts.
- [x] Remaining done: converter warnings localized at display time (`core/config/WarningLocalizer.kt`, 10 patterns RU/EN).

- [x] **DNS classification for Russia-restricted networks**: presets grouped by
      availability (plaintext RU / encrypted RU / global), bootstrap and
      proxy-server-nameserver pinned to RU-reachable resolvers, GLOBAL DoH
      choices get an automatic plaintext fallback.

### Deferred audit findings (medium/low)
- [x] Room: real migration v2 written, destructive fallback removed; `exportSchema` enabled with schemas committed under `app/schemas/`.
- [x] Android Private DNS restored at next root-mode startup after a daemon crash.
- [x] Encrypt subscription URLs and controller secret at rest — **done**: AndroidKeyStore AES-256-GCM (`enc1:` payloads), Room v4→v5 encrypts url/customHeaders in the migration, DataStore secret re-encrypted on first read; decrypt failures degrade to passthrough. Verified on emulator: legacy data migrated, enc1: confirmed.
- [x] Unify duplicated UI pieces: `SearchPill`, single latency badge, `CountBadge`.
- [x] TrafficGraph: upload gradient fill + idle placeholder. (axis labels/tooltip deliberately deferred — cosmetic)
- [x] Connection screen polling tied to screen visibility (DisposableEffect).
- [x] `DashboardViewModel` root-daemon polling reduced to 5s and skipped while the service is running.
- [x] YamlEditor: synced gutter + unsaved-changes dialog. (syntax highlight remains)
- [x] `usesCleartextTraffic` → networkSecurityConfig: cleartext forbidden by default in release (only 127.0.0.1/localhost for the core API); debug builds allow cleartext via manifest overlay. (Remaining: user toggle for http:// subscription sources.)
- [x] CI: release signing path verified locally with a dummy keystore via KEYSTORE_* env vars (apksigner confirms the certificate).

---

## 🔍 Security review (31.10.2026, 24 findings) — closure status

> Honest framing: this was an AI-assisted code review, not an independent audit with a named
> auditor. The project is AI-generated end to end with a single contributor who does not vouch
> for its security; treat it as experimental software (see README → Trust & threat model).

### Wave 1 — closed (merged in this milestone)
- [x] **P0.1** Deep-link hardening: unconditional import confirmation dialog (payload + source + name),
  panel behavior headers (autoconnect / per-app / **tun-mode→root switch**) applied only after explicit
  consent, `mihomet://` control links moved out of BROWSABLE into a non-BROWSABLE `activity-alias`,
  decision logic extracted into testable `DeepLinkPolicy` (fail-closed origin).
- [x] **P0.2** Core updater: only `mihomet/core` releases, RSA-4096 signed `manifest.json`
  (signature → sha256 → size → `mihomo -v` from the *new* binary) → backup + atomic install + rollback;
  fail-closed without manifest/signature. Signing key: `/mnt/Data/mihomet-keys/`, helper
  `scripts/sign-core-manifest.sh`. **Follow-up: mihomet/core releases must start shipping the signed
  manifest.json — until then the runtime updater refuses all updates (by design).**
- [x] **P1.1** ConfigGenerator profile-key whitelist (`listeners`, `tunnels`, `external-ui`, `geox-url`,
  `interface-name`, … dropped with warnings) + hostile-profile strict test.
- [x] **P1.2** Probe core: per-run random secret + ephemeral port + client closed.
- [x] **P2.2** No `runBlocking` on main thread (VpnService receiver, Application key gate → background
  coroutine + StateFlow, 5s bounded wait, conservative fallback).
- [x] **P2.3** `MihomoApiClient : Closeable`; provider closes swapped-out clients (30s grace) + `dispose()`
  from `onTerminate`; probe client closed in `finally`.
- [x] **P2.6** CI release gates: CHANGELOG heading check on `vX.Y.Z` tags, versionCode monotonicity vs
  the latest release's `version-info.json` (releases now ship it).
- [x] **P3.1** SECURITY.md rewritten to match reality (patched `mihomet/core` builds, per-run probe secret).
- [x] **P3.3** Garbled patch URL in `gradle/mihomo-core.properties` fixed.
- [x] **P3.4** CHANGELOG restructured by versions (1.2.1/1.2.0/1.1.1; no 1.1.2 tag exists).
- [x] **P3.6 (part)** `<queries>` MAIN/LAUNCHER instead of `QUERY_ALL_PACKAGES`; night-history branch
  pushed (README promise kept); locale parity guard test (333=333); GitHub issue/PR templates +
  CODE_OF_CONDUCT.

### Wave 2 — open (features delivered alongside)
- [x] Per-subscription custom HTTP headers (strict parser, injection guards, encrypted at rest,
  editor in profile card) with precedence **profile > global > default**.
- [x] Global subscription identity: User-Agent override + x-hwid toggle + custom hwid (Settings → Subscriptions).
- [x] Free MTU (1280–65535 numeric input + presets) and TUN stack choice instead of hardcodes.
- [x] App language picker (per-app locales, API 33+) + `localeConfig`.

### Feature: built-in auto-balancer "AUTO" — ✅ IMPLEMENTED (503 tests green; spec kept as design doc)

**Goal.** URI-list subscriptions (and panels with group-less templates) give nodes but no
balancing group. When the profile ends up without any balancer, Mihomet synthesizes its own
mihomo-native `url-test` group so the user gets Happ-style auto-selection. NO custom networking:
mihomo itself runs the latency probes and fails over — we only generate config.

**Core principle (how it works).** Group type `url-test`: mihomo periodically (interval) sends an
HTTP probe through every member, keeps the lowest-latency member active, and re-picks on failure.
`tolerance` (ms) prevents flapping between near-equal nodes. The dashboard already renders
url-test groups natively (Servers screen shows the current pick + latencies) — zero UI work.

**Multiple subscriptions.** Strictly per-profile: the balancer is synthesized inside ONE profile's
generated runtime config and balances only that profile's nodes. Cross-profile balancing is
explicitly OUT of scope (the core runs a single config; merging subscriptions would need
proxy-providers with per-provider auth headers — a separate large feature).

**Design decisions (locked):**
1. Trigger: after profile parse, if the effective group list contains NO group of type
   `url-test` / `load-balance` / `fallback`, AND the real-node pool (see 4) has >= 2 nodes,
   AND `autoBalancerEnabled` — synthesize the group. Panel-provided balancers always win
   (never duplicate, never modify a panel balancer).
2. Name: constant `AUTO` (add `CoreConstants.AUTO_GROUP_NAME = "AUTO"` — matches the existing
   `PROXY` convention; visible in Servers screen as-is).
3. Group fields: `type: url-test`, `udp: true`, `url: "https://www.gstatic.com/generate_204"`
   (constant in CoreConstants), `interval: 300`, `tolerance: 50`.
4. Pool = profile's real proxies (the same `realProxyNames` list the fallback PROXY synthesis
   uses, already excluding reserved names) MINUS every name containing any user exclude
   pattern (case-insensitive substring match, patterns split by newline; empty setting = no
   exclusions). Real-world motivation: bridge nodes («Мост …»), gaming nodes («[🎮 Игровой]»),
   bypass nodes («ОБХОД») balance badly — let the user cut them out.
5. Integration with groups:
   - If the app synthesizes the fallback `PROXY` select group anyway (panel gave no groups):
     PROXY proxies = `[AUTO, ...ALL real nodes]` — AUTO is the default pick, but EXCLUDED nodes
     stay manually selectable in PROXY (deviation from the draft: excluding a node from the
     balance pool must not hide it from manual selection).
   - If the panel DID provide groups (but no balancer): AUTO is APPENDED to the proxy list of
     the FIRST `select`-type group (available, NOT the default pick). If the setting
     `autoBalancerPreferDefault` is ON, prepend it instead (AUTO becomes that group's default).
     Never touch non-select panel groups.
6. Settings (DataStore via SettingsRepository, global — per-profile overrides are future work):
   - `autoBalancerEnabled: Boolean = true`
   - `autoBalancerExcludes: String = ""` (newline-separated substrings)
   - `autoBalancerPreferDefault: Boolean = false`
7. Settings UI: a card in the Advanced section («Продвинутые») titled «Авто-балансировщик»:
   enable toggle, excludes multiline field (hint: one substring per line, e.g. `Мост`,
   `Игровой`), and the prefer-default toggle with an explicit warning-style subtitle that
   enabling changes the default routing target for panel select-groups. ALL strings in BOTH
   locales via stringResource; follow SubscriptionsSection/AdvancedSection patterns
   (SettingsScreen is split — the card lives in its own file or AdvancedSection).
8. Files touched: `core/config/ConfigGenerator.kt` (synthesis + injection, near the existing
   fallback PROXY logic ~lines 300-335), `CoreConstants.kt` (constants), `data/preferences/`
   (settings fields + sanitizer like other string settings), `ui/screens/settings/` (card),
   `res/values*/strings.xml` (append at END, both locales), tests.
9. Tests (strict style, extend ConfigGeneratorStrictTest / ConfigGeneratorWhitelistStrictTest):
   - synthesized when no groups; fields exact (type/url/interval/tolerance/udp);
   - NOT synthesized when a panel url-test/load-balance/fallback group exists;
   - not synthesized with < 2 pool nodes after exclusions; exclusions applied
     case-insensitively;
   - fallback-PROXY path: AUTO first in PROXY list, MATCH,PROXY unchanged;
   - panel-select-group path: appended by default, prepended when prefer-default ON,
     panel non-select groups untouched;
   - panel balancer + feature ON → zero changes to panel groups.
10. Out of scope (do NOT build): cross-profile balancing, per-profile settings, probe URL /
    interval settings UI, latency-test integration with NodePingTester.

Verification: `./gradlew compileDebugKotlin testDebugUnitTest` green; build with
`JAVA_HOME=/home/misha/.jdks/jdk-21`.
### Wave 3 — build-stack & refactors (closed this milestone)
- [x] **P2.1** God files split: `MihometVpnService` 961→467 (+TunBuilder/CoreProcessLauncher/
  CoreReadinessProbe/CoreWatchdog/VpnNotificationController/UnderlyingNetworkTracker),
  `SettingsScreen` 2232→239 (+12 per-section files), `UniversalConfigConverter` 1714→67 facade
  (+per-format converters). Public APIs preserved, all fixture tests unmodified.
- [x] **P2.9** Typed `ConversionWarning` sealed hierarchy end-to-end; Russian-prefix matching in
  `WarningLocalizer` deleted; Russian literals removed from converters. (Exception path messages
  still Russian — tracked below.)
- [x] **P2.4** Dependency ladder: core-ktx 1.18, activity-compose 1.13, lifecycle 2.10,
  navigation 2.9.8, WorkManager 2.12, DataStore 1.3, Compose BOM 2026.06, Room 2.8.5 (KSP2),
  kotlinx-serialization 1.8.1, Koin 4.1.1, splashscreen 1.2.0, AGP 8.13.2 + Gradle 8.13,
  **Ktor 2.3.12 → 3.3.3** (latest consumable on Kotlin 2.1; 3.4+ needs the Kotlin bump),
  MockWebServer 5.2.1; ben-manes versions plugin + weekly `dependencyUpdates` CI job.
  Deliberately deferred: Kotlin 2.3 / AGP 9 (their own migration steps).
- [x] **P2.5** compileSdk 37, targetSdk 36.
- [x] **P2.7** CI: Android Lint (baseline 109 pre-existing) + detekt (baseline 70 IDs, file/function
  size limits) — new code must be clean.
- [x] **P2.8** Honest ICMP: `/system/bin/ping` via ProcessBuilder, feature-detected, distinct
  "unavailable on this device" state (never fakes a timeout); output parser unit-tested
  (toybox/iputils/busybox).
- [x] **P2.10** ABI splits: per-ABI APKs, no universal (arm64 install ≈ half the old fat APK).
- [x] **P2.11** Shotgun R8 keeps removed; verified by a SIGNED release APK smoke test on the emulator
  (launch, screens, profile import, real VPN connect, clean stop; 0 stripped-code crashes; no keeps
  re-added). Bonus find: YAML editor crashed on every profile (highlighter didn't mirror the original
  text) — fixed in the same milestone.
- [x] **P1.3** Instrumented tests: 14 tests / 4 classes (Room migrations incl. Keystore round trip,
  deep-link manifest contract, VpnService manifest contract, ConfigGenerator on-device whitelist
  smoke) + `connected` CI job on API 29+35 emulator matrix.
- [x] **P1.4** In-app self-update (Settings → About): semver check against GitHub releases,
  fail-closed verifier (version-info.json → tag binding → versionCode monotonicity → asset
  sha256/size → **signing-certificate equality**), streamed download + PackageInstaller with
  the user-consent flow; releases without `version-info.json` (e.g. published v1.2.1) degrade
  to "open release page" only. Verified on-device: real GitHub round trip.
- [x] **P2.9 (tail)** Fail-closed errors typed end-to-end (`AppErrorCode`/`AppException` +
  resource mapping, guarded by `AppErrorResourcesTest` in both locales) — no Russian literals
  left in core/data layers.
- [x] **P3 (rest)** Signed tags for releases (SSH-signing via ~/.ssh/id_ed25519 + GitHub API tag-signature verification gate in release.yml; verified on v1.3.1).
- [x] **P1.3 (addendum)** JaCoCo unit-test coverage report in CI (artifact, no hard gate).
- [x] **v1.3.0 RELEASED** (tag on d0d2338, CI-signed, cert SHA-256 `b85405a2…` verified;
  per-ABI APKs ≈30 MB; version-info.json published → the self-updater has its baseline).
  Release gate bug fixed en route: the versionCode-monotonicity step died via pipefail when
  the previous release had no version-info.json.
- [x] **v1.3.1 RELEASED** (tag on eb7451a, GitHub API tag-signature verified, CI-signed with prod cert
  `b85405a2…`, per-ABI APKs ≈29-31 MB, version-info.json published; release notes set from CHANGELOG;
  built-in auto-balancer AUTO + quiet app-managed drops shipped).

---

## 📋 Completed Milestones
- [x] Full UI Redesign to Material 3 Expressive (Pills, ExpressiveShapes, Haptics).
- [x] Pure Carbon Neutral AMOLED theme (zero purple, `R==G==B` containers, white/titanium accents).
- [x] Smooth disconnect animation with contracting halo and amber spin-down indicator.
- [x] Removal of intrusive "ядро остановлено" snackbars.
- [x] Shared-axis lateral slide navigation transitions (`NavGraph.kt`).
- [x] Multi-line auto-expanding custom headers field with internal scrolling (`ProfilesScreen.kt`).
- [x] Brand logo (M-route monogram), adaptive icon with monochrome layer, splash screen.
- [x] Core downloaded at build time with pinned sha256 (repo slimmed from 154MB to ~1MB of source).
- [x] Security hardening: random controller secret, LAN access disabled, allowBackup=false, settings-aware API client.
- [x] Converter: TUIC / Hysteria v1 / IPv6 parsing + non-silent warnings.
- [x] WebSocket auto-reconnect, ping concurrency cap, error surfacing in Profiles/Proxies.
- [x] GPL-3.0 license, GitHub Actions CI + release workflows, SECURITY/CONTRIBUTING docs.
