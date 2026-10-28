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
| Sub-Store / subconverter pipelines | ❌ untested | exotic output variants |

### Phase 1: Docker test bench
- [ ] **1.1. Testing directory**: `docker/test-bench/` with modular compose files and docs.
- [ ] **1.2. 3X-UI deployment** (`ghcr.io/mhsanaei/3x-ui`): pre-configured SQLite with VLESS Reality, VMess+WS, SS-2022, Trojan+gRPC inbounds; exposed subscription endpoint.
- [ ] **1.3. Marzban deployment** (`gozargah/marzban`): test admin/users, Clash & sing-box subscription endpoints, `Subscription-Userinfo` header verification.
- [ ] **1.4. Marzneshin deployment**: both subscription formats, user-info headers.
- [ ] **1.5. Remnawave deployment**: HWID enforcement (`x-hwid`), UA validation, balancer grouping.
- [ ] **1.6. S-UI and Hiddify deployments**: subscription output snapshots.

### Phase 2: Converter integration tests
- [x] **2.1. Fixture-based unit tests**: `PanelsSubscriptionTest` scans `app/src/test/resources/panels/<panel>/`
      (real payloads captured from the live docker bench) and asserts every node survives conversion.
      Any new fixture is picked up automatically.
- [ ] **2.2. Base64 resilience**: RFC 4648 without padding, URL-safe variants, multi-line payloads.
- [x] **2.3. `Subscription-Userinfo` support**: parsed on add/update, quota bar + expiry date (colored by remaining days) in profile cards. Room 1→2 migration written without data loss.
- [ ] **2.4. Header retention**: keep custom headers (`x-hwid`, custom `User-Agent`) during background profile updates.
- [ ] **2.5. Group alignment**: panel-defined selectors/url-test groups import into `ProxiesScreen` without conflicts.

### Phase 3: Localization RU/EN — ✅ done
- [x] ~215 hardcoded strings extracted into `res/values/strings.xml` (EN default) and `values-ru/strings.xml` (235 entries each).
- [x] ViewModel statuses emit resource ids with format args; plurals for import counts.
- [ ] Remaining: localize converter `warnings` at display time (currently Russian data strings).

### Deferred audit findings (medium/low)
- [x] Room: real migration v2 written, destructive fallback removed. (Remaining: `exportSchema` for future schema diffs.)
- [x] Android Private DNS restored at next root-mode startup after a daemon crash.
- [ ] Encrypt subscription URLs and controller secret at rest (Room/DataStore).
- [x] Unify duplicated UI pieces: `SearchPill`, single latency badge, `CountBadge`.
- [x] TrafficGraph: upload gradient fill + idle placeholder. (axis labels/tooltip remain)
- [x] Connection screen polling tied to screen visibility (DisposableEffect).
- [x] `DashboardViewModel` root-daemon polling reduced to 5s and skipped while the service is running.
- [x] YamlEditor: synced gutter + unsaved-changes dialog. (syntax highlight remains)
- [x] `usesCleartextTraffic` → networkSecurityConfig: cleartext forbidden by default in release (only 127.0.0.1/localhost for the core API); debug builds allow cleartext via manifest overlay. (Remaining: user toggle for http:// subscription sources.)
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
