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
- [x] **2.4. Header retention**: custom headers (incl. `x-hwid`, custom `User-Agent`) kept in auto-refresh via SubscriptionRepository.
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
- [x] Room: real migration v2 written, destructive fallback removed. (Remaining: `exportSchema` for future schema diffs.)
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

## 🔍 External audit (31.10.2026, 24 findings) — closure status

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

### Wave 3 — remaining (audit long tail)
- [ ] **P1.3** Instrumented tests: `connectedAndroidTest` on API 29/35 emulators in CI (VpnService smoke,
  deep-link import, Room migrations); JaCoCo coverage gate.
- [ ] **P1.4** In-app update check (GitHub releases; signature-checked APK install — see Tropa Updater.kt).
- [ ] **P2.1** Split god files: `MihometVpnService.startVpnInternal` (~500 lines) → TunBuilder /
  CoreProcessLauncher / NotificationController; `UniversalConfigConverter` (1.7k) → per-format
  converters; `SettingsScreen` (1.7k) → per-section files.
- [ ] **P2.4** Dependency ladder: AndroidX + Compose BOM → Room 2.8 → Koin 4 → AGP 9/Kotlin 2.3 →
  Ktor 3; add `ben-manes/gradle-versions` job.
- [ ] **P2.5** `compileSdk 37`, `targetSdk 36` after emulator matrix run.
- [ ] **P2.7** Static analysis in CI: Android Lint (`abortOnError`/baseline), detekt with file/function
  size limits.
- [ ] **P2.8** ICMP honestly via `/system/bin/ping` (feature-detect) or drop "ICMP" from UI.
- [ ] **P2.9** Typed `ConversionWarning` instead of RU-prefix string matching in `WarningLocalizer`.
- [ ] **P2.10** ABI splits (or runtime core download → APK <10MB).
- [ ] **P2.11** Trim shotgun R8 keeps (`io.ktor.**`, `org.yaml.snakeyaml.**`, `org.koin.**`) after
  release-build smoke test.
- [ ] **P3 (rest)** Signed tags/GPG for releases; doc drift sweep (TODO `exportSchema` note — schemas
  2..5 exported; `docs/compatibility-research.md` keyring note); versionCode still manual (5) — bump
  on next release.

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
