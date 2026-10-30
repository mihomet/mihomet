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
| Виджет рабочего стола + тумблер | ✅ MihometWidgetProvider |
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
