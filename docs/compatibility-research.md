# Compatibility with the Happ / Incy / CMFA / FlClash ecosystem

*Russian version: [compatibility-research.ru.md](compatibility-research.ru.md)*

> Research results (sources: FlClash `chen08209/FlClash`, CMFA `MetaCubeX/ClashMetaForAndroid`,
> Happ dev-docs `HappDev/happ_su`, Incy docs `INCY-DEV/incy-docs`, panel implementations
> `MHSanaei/3x-ui internal/sub/happ.go|incy.go`, Remnawave SRR presets).

## Deep-link schemes (support in mihomet: `core/DeepLinkParser.kt`)

| Scheme | Format | Status |
|---|---|---|
| `clash://install-config?url=&name=&type=url&update-interval=` | CMFA/FlClash, host must be exactly install-config | ✅ |
| `clashmeta://…`, `flclash://…` | same as clash | ✅ |
| `v2rayng://install-sub?url=` | v2rayNG | ✅ |
| `sing-box://import-remote?url=` | SFA | ✅ |
| `happ://add/<base64-payload>` | one-tap node add | ✅ |
| `happ://routing/add|onadd/{base64-JSON}` | routing profile (GlobalProxy, DirectSites…) | ✅ (onadd = import + activate) |
| `happ://crypt4/`, `crypt5/` | encrypted subscription (AES-128-GCM, `crypto.happ.su`) | ✅ decryption per spec (keyring in DataStore, IV 'kkkkkkkkkkkk', tag in encrypt-tag); keyring UI ready (Settings → Advanced) |
| `incy://add/<url>`, `incy://import/{data}` | Incy | ✅ |
| `incy://crypt1/<payload>` | base64url(iv‖ct‖tag) | passthrough (warning) |
| `incy://connect|disconnect|toggle`, `mihomet://start|stop` | VPN control via link | ✅ (MainActivity.handleControlLink) |

## Cats VPN (<redacted-remnawave-host> — Remnawave + Happ HWID gating), 2026-11-01

The panel routes content based on **two headers at once** — reproduced with curl:

| Request condition | What the panel returns |
|---|---|
| `User-Agent: Mihomet/*` **without** `x-hwid` | 732 B — fake placeholder nodes: `vless://…@0.0.0.0:1#❌ Данное приложение не поддерживается` / `#✅ Включите HWID в настройках Happ` |
| `Mihomet/*` + valid `x-hwid` | ~11 KB — **base64 URI list** (real vless nodes). There are never any groups in this format → the app builds a fallback PROXY group |
| `ClashMetaForAndroid/*` / `clash-verge/*` + `x-hwid` | ~11 KB — **Clash YAML** with a single `→ Remnawave` group (select, all nodes) |
| `Happ/*` + `x-hwid` | ~180 KB — Happ format |

Conclusions: (1) hwid is mandatory — without it any UA gets the placeholder stubs;
(2) the UA determines the **response format**: unknown clients get a URI list,
Clash clients get YAML; (3) the panel templates currently contain no
url-test/load-balance groups at all (the "auto-balancer" disappeared on the panel
side, not in the client). Recommendation for users of such panels: Settings →
Subscriptions → User-Agent = `ClashMetaForAndroid/2.11.5.Meta` (or a per-subscription
`User-Agent` header) to receive YAML with a group.

## Headers sent/read by Happ and Incy

- **UA**: Happ sends `Happ/<ver>`, Incy sends `INCY/<ver>/<platform>`. SRR panels (Remnawave
  Subscription Response Rules) match the UA and return `XRAY_JSON` / `MIHOMO` / `SINGBOX` /
  `XRAY_BASE64` / HTML. mihomet sends an honest `Mihomet/<ver>` first (lands in the MIHOMO tier),
  followed by Happ/sing-box/ClashMeta/v2rayNG UA fallbacks.
- **`x-hwid`** (alnum 10–64) — device binding for connection limits (SHA-256 of ANDROID_ID).
- Happ headers: `profile-title`, `profile-update-interval`, `subscription-userinfo`,
  `New-Url` / `Fallback-Url` (automatic subscription relocation), `ProviderID`, `Sub-Info-Color`,
  `Subscription-Always-Hwid-Enable`, `Tun-Mode`, `Per-App-Proxy-Mode/List`, `encrypt-tag`.
- Incy adds: `support-url`, `announce-url`, `banner-*`, `sort-order` (`base64:<…>`),
  `fragmentation-*`, `noises-*`.
- Subscription content encryption: AES-128-GCM, IV `"kkkkkkkkkkkk"`, tag `encrypt-tag`
  (mihomet does not support this yet — passthrough with a warning).

## Borrowed patterns (FlClash / CMFA) — already in mihomet

- own-uid exclusion in the VPN builder (`addDisallowedApplication(packageName)`) ✅
- Stop button in the notification ✅
- `setUnderlyingNetworks` + NetworkCallback on network change ✅

## Roadmap (from the TOP-8 research)

1. ✅ Deep-link import (see the table)
2. ✅ Core watchdog (auto-restart with backoff, 3 attempts)
   Attribution: non-root TUN core patch taken from Tropa (nvsbunny/tropa,
   tools/mihomo-android-nonroot.patch @ v0.2.5) — the mihomet/core repo,
   GPL-3.0-compatible. VERIFIED on API 35 emulator (non-root): TUN adapter up
   (no packages.xml error), real app traffic routed via the selected proxy node
   (mihomo.log: [TCP] … match Match using → Remnawave[🇫🇷]).
   API-LEVEL MATRIX (emulator): API 26 minSdk ✅ (TUN up + routed traffic + deep-link
   import), API 29 ✅ (TUN up), API 35 ✅. Found+fixed by the matrix: the first patched
   build targeted NDK API 34 and silently exited on Android 8-13 — rebuilt targeting
   android-26 (core-v1.19.33-mihomet.2). Audit tails closed:
   readiness-gate before 'connected', watchdog stability reset, mode-switch
   serialization + root-firewall cleanup on VPN start, updater rollback guard,
   empty-whitelist semantics + own-package loop guard, YAML highlighting.
3. ✅ Honest UA `Mihomet/<ver>` + `x-hwid` + profile-title / New-Url
4. ✅ Builder: `setBlocking(false)`, `setMetered(false)`, `setConfigureIntent`
5. ✅ `FOREGROUND_SERVICE_IMMEDIATE` + core watchdog (auto-restart ×3) + Always-on (null-intent restart, onRevoke) + root-daemon watchdog (10s monitor, backoff ×3) + automatic subscription refresh per update-interval (WorkManager)
6. ✅ Network change → reconnect core connections (UnderlyingNetworkTracker: setUnderlyingNetworks + closeAllConnections), screen-on speed refresh (VpnNotificationController screenStateReceiver)
7. ✅ Always-on VPN + `onRevoke` graceful (MihometVpnService: onStartCommand null-intent restart + onRevoke cleanup)
7.5 ✅ Additionally implemented (morning session + waves from the repeat audit):
   - Fixed: iptables whitelist inversion, IPv6 mark 20/25 by family, fail-closed
     on an invalid profile + last-known-good, mixed-port removed, Keystore lifecycle,
     readiness gate (/configs tun.enable), OEM chain save/restore, updater rollback.
   - In progress (wave A): firewall DNS-hijack for TCP:53 + unified empty-whitelist,
     VPN exit-ownership/cleanup, root readiness + rollback, watchdog stability window.
   - Wave A (done): firewall DNS-hijack for TCP:53 + unified empty-whitelist,
     VPN exit-ownership/cleanup, root readiness + rollback, watchdog stability window.
   - Wave H (in progress): services (missing selected-profile file = fail-closed,
     semantic validation before start, per-app actually-added packages,
     serialization of root start/stop, VPN last-good regeneration), atomic
     OEM snapshot (tmp+mv, flush gated), conservative Keystore gate (enc1: in
     DataStore/Room/keyring), encrypt throws instead of silently falling back to plaintext.
   - Remainder (P2): DoH UI disclaimer, updater scope disclosure ready (partially),
     subconverter, JS-override — in TODO.
 Desktop widget (status+toggle),
    autostart on boot (last successful mode), TUN stack/MTU/bypass-LAN (CMFA
    additive pattern), subscription QR code, 20 MB body streaming limit, log export/sharing,
    memory/connections on the dashboard. N3 in progress: battery prompts, allowBypass, HTTP proxy
    via VPN.
8. ◐ Override layer on top of the profile: the basic typed version is done (unified-delay / tcp-concurrent / log-level / sniffer in Settings → Advanced, taking priority over the profile); the per-profile override patch is done (Room v4 `overridePatch`, YAML fragment in the profile menu, deep merge into the runtime config BEFORE the mandatory settings — the app's allow-lan/ports/DNS always take priority). Remainder (L): full dns block (nameserver-policy, fake-ip-filter-mode), per-protocol sniffer, JS scripts like in FlClash.

## Overnight bench sync (2026-11-02) — format coverage and fixed gaps

Fixtures re-captured from all 7 panels (fresh containers), added
`PanelConversionCoverageTest`: 32 fixtures × (nodes/groups/warnings) — table in the test.

Fixed in `XrayJsonConverter`:
1. **Flat vless/vmess form from 3x-ui** (`settings = {address, port, id, flow}` without
   `vnext[]`) — used to be dropped silently (4 outbounds → 2 nodes, 0 warnings). Both
   forms are now parsed; the node `id` is taken from `vnext[i].users[0]` (two-level
   structure — do not flatten!).
2. **Hysteria v1 is no longer masked as hysteria2** — a separate branch with
   `up`/`down`/`password`/alpn h3 (mihomo supports v1 natively).
3. **Silent losses are forbidden**: an unparsed outbound → `ProxyParseError` warning;
   service protocols (freedom/blackhole/dns/dokodemo-door/api) are skipped without noise.

Verified and left as-is:
- sing-box: ssh/naive/mieru outbounds do not map to mihomo (no such types) — they end up
  in warnings (Hiddify sing-box: 42/50 nodes + 8 warnings — correct).
- Hiddify URI schemes: snell/ssh/wg/hysteria/hy2/tuic — the parser supports snell and wg;
  mierus/snell duplicates and ssh end up in warnings.
- Sub-Store returns `raw-uris` without groups for all targets — expected (a group-less pool).

Content-aware UA fallback (`SubscriptionContentChoice` + the chain
`[Mihomet, mihomo/v1.19.33, Happ, sing-box, ClashMeta, v2rayNG]`): the first response
with nodes+groups stops the chain; a group-less response is kept as a fallback
while attempts continue. The decision is extracted into a pure object
(`SubscriptionContentChoiceTest`, 7 tests) — the repository cannot be constructed on the JVM.
