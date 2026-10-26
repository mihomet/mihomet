# Mihomet — Project Roadmap & TODO

## 🎯 Active Milestone: Docker Test Bench with Real VPN Panels (Option A)

**Objective**: Deploy popular VPN control panels in Docker with realistic mock node configurations to test and guarantee 100% accurate subscription fetching, format conversion, and proxy group handling in Mihomet.

---

### Phase 1: Docker Compose Setup & Deployment
- [ ] **1.1. Create Testing Directory**: Setup `docker/test-bench/` with modular compose definitions and documentation.
- [ ] **1.2. 3X-UI Panel Deployment**:
  - Image: `ghcr.io/mhsanaei/3x-ui:latest` (or `alireza0/x-ui`).
  - Pre-configure SQLite database with fake test inbounds:
    - VLESS Reality (with `pbk`, `sid`, `sni`, `fp`).
    - VMess + WebSocket (`path`, `host`, `tls`).
    - Shadowsocks (2022-blake3-aes-128-gcm / chacha20-poly1305).
    - Trojan + gRPC.
  - Expose subscription endpoint for testing.
- [ ] **1.3. Marzban Panel Deployment**:
  - Image: `gozargah/marzban:latest` with Xray core.
  - Setup test admin & users.
  - Configure subscription with standard and Clash/Sing-box endpoints.
  - Verify `Subscription-Userinfo` traffic header emission.
- [ ] **1.4. Remnawave Panel Deployment**:
  - Image: Remnawave backend/frontend stack.
  - Configure HWID enforcement (`x-hwid`) and User-Agent validation.
  - Test anti-abuse and node balancer grouping.

---

### Phase 2: Conversion & Integration Testing in Mihomet
- [ ] **2.1. Subscription Import Automated Tests**:
  - Write test cases in `SubscriptionStrictTest` fetching directly from local Docker endpoints (or generated snapshots).
  - Verify Base64 padding resilience (`RFC 4648` without `=` or URL-safe variants).
  - Verify Sing-box JSON -> Mihomo YAML conversion via `UniversalConfigConverter`.
- [ ] **2.2. Header & Traffic Info Support in Mihomet**:
  - Parse `Subscription-Userinfo` header (Upload, Download, Total, Expiry).
  - Display quota progress bar & expiry date in `ProfilesScreen` cards.
  - Retain custom headers (`x-hwid`, custom `User-Agent`) during background profile updates.
- [ ] **2.3. Proxy Group & Balancer Alignment**:
  - Verify panel-defined selector and url-test groups import seamlessly into `ProxiesScreen` without conflicts.

---

## 📋 Completed Milestones
- [x] Full UI Redesign to Material 3 Expressive (Pills, ExpressiveShapes, Haptics).
- [x] Pure Carbon Neutral AMOLED theme (zero purple, `R==G==B` containers, white/titanium accents).
- [x] Smooth disconnect animation with contracting halo and amber spin-down indicator.
- [x] Removal of intrusive "ядро остановлено" snackbars.
- [x] Shared-axis lateral slide navigation transitions (`NavGraph.kt`).
- [x] Multi-line auto-expanding custom headers field with internal scrolling (`ProfilesScreen.kt`).
- [x] Comprehensive developer & AI agent guide (`AGENT_GUIDE.md`).
- [x] 36/36 Unit Tests Passing (`ThemeStrictTest`, `SubscriptionHeadersStrictTest`, `ConfigGeneratorStrictTest`, `NodePingTesterStrictTest`, `DashboardStateStrictTest`).
- [x] Verified and signed release APK assembly.
