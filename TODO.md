# Mihomet — Project Roadmap & TODO

## 🎯 Active Milestone: Full Panel Compatibility for the Subscription Converter

**Objective**: Guarantee 100% accurate subscription fetching and format conversion for all popular VPN panels by testing against real Docker deployments and recorded fixtures.

### Supported / target panels matrix

| Panel | Status | Notes |
|---|---|---|
| Generic (Base64 URI list, Clash YAML, sing-box JSON) | ✅ working | `UniversalConfigConverter` |
| Share-links (vless/vmess/trojan/ss/hysteria2/hysteria/tuic) | ✅ working | incl. IPv6 hosts |
| Marzban | ⚠️ partial | endpoints `/sub/{token}/clash`, `/sing-box`, HTML fallback |
| Remnawave | ⚠️ partial | `x-hwid` header supported; response format snapshots needed |
| 3X-UI / S-UI | ⚠️ partial | Base64 URI lists, `/sub` endpoints |
| Marzneshin | ❌ untested | subscription variants + user-info headers |
| Hiddify Panel | ❌ untested | `sub?...` formats, remote profiles |
| Sub-Store / subconverter pipelines | ❌ untested | exotic output variants |

### Phase 1: Docker test bench
- [ ] **1.1. Testing directory**: `docker/test-bench/` with modular compose files and docs.
- [ ] **1.2. 3X-UI deployment** (`ghcr.io/mhsanaei/3x-ui`): pre-configured SQLite with VLESS Reality, VMess+WS, SS-2022, Trojan+gRPC inbounds; exposed subscription endpoint.
- [ ] **1.3. Marzban deployment** (`gozargah/marzban`): test admin/users, Clash & sing-box subscription endpoints, `Subscription-Userinfo` header verification.
- [ ] **1.4. Marzneshin deployment**: both subscription formats, user-info headers.
- [ ] **1.5. Remnawave deployment**: HWID enforcement (`x-hwid`), UA validation, balancer grouping.
- [ ] **1.6. S-UI and Hiddify deployments**: subscription output snapshots.

### Phase 2: Converter integration tests
- [ ] **2.1. Fixture-based unit tests**: record real subscription payloads from every panel into `app/src/test/resources/panels/<panel>/*.txt` and assert full conversion (proxies, groups, no dropped nodes, no warnings).
- [ ] **2.2. Base64 resilience**: RFC 4648 without padding, URL-safe variants, multi-line payloads.
- [ ] **2.3. `Subscription-Userinfo` support**: parse Upload/Download/Total/Expiry, show quota bar + expiry in `ProfilesScreen` cards.
- [ ] **2.4. Header retention**: keep custom headers (`x-hwid`, custom `User-Agent`) during background profile updates.
- [ ] **2.5. Group alignment**: panel-defined selectors/url-test groups import into `ProxiesScreen` without conflicts.

### Phase 3: Localization RU/EN (in progress — next task)
- [ ] **3.1.** Extract ~200 hardcoded Russian strings from 7 screens + ViewModel statuses into `res/values/strings.xml` (EN default) and `values-ru/`.
- [ ] **3.2.** ViewModels emit resource ids (not literals); plurals for node/connection counters.
- [ ] **3.3.** Update tests asserting on literal strings.

### Deferred audit findings (medium/low)
- [ ] Room: enable `exportSchema` and write real migrations (currently destructive).
- [ ] Restore Android Private DNS after a root-daemon crash (currently only on clean stop).
- [ ] Encrypt subscription URLs and controller secret at rest (Room/DataStore).
- [ ] Unify duplicated UI pieces: `SearchPill` (3 screens), two latency badges, count badges.
- [ ] TrafficGraph: upload gradient fill, axis labels, tap tooltip.
- [ ] Connection screen polling tied to screen visibility instead of VM init.
- [ ] `DashboardViewModel` root-daemon polling via root shell every 3s → event-based.
- [ ] YamlEditor: highlight syntax, keep gutter in sync (single scroll), unsaved-changes dialog.
- [ ] `usesCleartextTraffic` → networkSecurityConfig allowing cleartext only for user-declared http subscription hosts.
- [ ] CI: release workflow test with a dummy keystore secret.

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
