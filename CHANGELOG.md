# Changelog

## 1.3.5

### Security (property-based fuzzing)
- CR/LF injection guard: URL-decoded node names from subscriptions can no longer
  carry control characters into the YAML config or log lines (found by mutation
  fuzzing, ParserPropertyTest — 600-iteration no-throw / no-injection / no-silent-
  loss invariants over every share-link scheme and mutated YAML/JSON payloads)
- The Hiddify expiry pseudo-link marker is now recognized in any scheme (was
  trojan-only and leaked through as a garbage node)

### Compatibility
- Reverse-engineering notes for Happ 4.9.0 and Incy 3.9.8 documented
  (docs/reverse-happ-incy.md): both are Xray-core clients; deep-link scheme
  inventory, header inventory, Incy's hwid registration + Appwrite backend

### i18n & docs
- README is English-primary with a full Russian version (README.ru.md);
  compatibility research, roadmap and reverse notes translated to English
  (Russian originals preserved as .ru.md)
- Locale-aware date formatting; ASCII fallbacks in connection test results

### UI (Material 3 Expressive pass)
- Dashboard hero: press squeeze + state-differentiated ring motion
  (connecting pulse / connected breathing / disconnecting spin-down)
- Servers: unified latency grading (green ≤200 / amber ≤500 / red), animated
  node selection, expressive empty state
- Settings: unified section rhythm, translucent theme-tinted accent chips
  (fixes heavy dark patches in the light theme), whole-row press feedback
- Brand palette is the default on fresh installs (Material You stays a toggle)

### Build
- Geo databases verified against the .sha256sum published in the same
  MetaCubeX release (routing-integrity; corruption caught, upstream
  republishes self-heal)
- GitHub Actions pinned by commit SHA

## 1.3.4

### Hardening (external supply-chain review round 2)
- Zero cleartext probe URLs in the app: latency/probe defaults and fallbacks are
  https end-to-end; the legacy https→http downgrade for the Cloudflare endpoint
  and the stored test URL is removed
- GitHub Actions pinned by commit SHA (workflows + core repo)
- Release builds without KEYSTORE_* are truly unsigned (were silently debug-signed)
- Honest provenance: Trust & threat model in README; review reframed as
  AI-assisted internal, not an independent audit
- Bench fixtures: real VPS IP replaced with RFC 5737 documentation IP;
  provenance notice added; mihomet/core gained a LICENSE file and SHA-pinned,
  manifest-signing CI (CORE_MANIFEST_KEY secret)

## 1.3.3

### Panel-format conversion — full coverage
- Xray JSON: flat vless/vmess settings (3x-ui export) now parse (were silently
  dropped); two-level vnext[i].users[0].id structure respected; hysteria v1 kept
  as hysteria (not mislabeled hysteria2); http/socks outbounds mapped; TCP+http
  obfs → http-opts; httpupgrade-opts; kcp-opts (mtu/seed/header); grpc
  multi-mode; WS early-data + all headers; TUIC v5 full params
- sing-box: httpupgrade is its own network (was folded into ws); multiplex →
  smux; anytls/shadowtls/http outbounds; hysteria v1 password + bandwidth;
  TUIC both congestion field spellings; ss plugin_opts parsed into
  plugin-opts map
- URI list: xhttp/httpupgrade/kcp/tcp-obfs params; vmess scy cipher;
  ss2022 SIP002; hysteria2 mport/obfs; snell obfs-host; SIP003 plugins
  (simple-obfs, v2ray-plugin)
- Content-aware UA fallback: the chain keeps walking while responses come back
  without proxy-groups and prefers the first grouped response — panels that
  serve bare URI lists to unknown clients (Remnawave-type) now yield their
  groups without any panel-side changes
- Dropped outbounds always warn (no silent losses); PanelConversionCoverageTest
  walks 32 fresh fixtures from all 7 bench panels
- Hysteria v1 bandwidth fallbacks: automatic `50 Mbps` upload / `100 Mbps` download
  defaults across runtime config generator and converters (SingBox, Xray, URI list)
  when panel templates omit `up`/`down`, preventing fatal Mihomo core parse crashes

### UI
- Servers screen: expressive empty state (icon + hint, both locales)
- Brand palette is the default again — Material You wallpaper tones were hiding
  the designed identity on Android 12+ (toggle stays for Material You fans)

## 1.3.2

- Notification layout: active profile name in title, duration timer in native header subText, live speed (↓ / ↑) on a dedicated line with non-breaking spaces preventing mid-unit wrapping
- Notification performance: added setOnlyAlertOnce and paused updates when screen is turned off for battery savings
- Network handover: seamless transitions (Wi-Fi ↔ LTE, LTE ↔ LTE, Wi-Fi ↔ Wi-Fi) without dropping connections on RSSI changes
- Quick Settings tile: enlarged 5-node route glyph to 21.5dp; tap opens client directly; fixed long-press intent resolution (CATEGORY_DEFAULT)
- Launcher icon: scaled foreground safe area to standard 40dp (0.72x) matching Android system grid
- Edge-to-edge UI: eliminated double status-bar and navigation-bar padding (no wasted space at top/bottom of screens)
- Material 3 Expressive motion: directional Shared-Axis X page transitions
- Proxies: protocol transport badge (VLESS, Reality, TLS, WS, gRPC, QUIC) + parallel ping with progressive updates
- Balancers: resolved multi-node outbounds in Happ Xray subscriptions and removed phantom nodes from server list
- Logging: configurable log levels + continuous background logging toggle
- Subscriptions: auto-update interval selection in dropdown menu (1h, 6h, 12h, 24h)
- Core memory usage: fixed WebSocket streaming from Mihomo core

## 1.2.1 — external audit recheck (hardening wave 2)

### Root / firewall
- Per-command verification of root shell results, TPROXY post-check,
  mode-switch serialization, atomic OEM rule publishing (recheck N03/N04/N06)

### Security
- Subscription data (URL, headers) is encrypted BEFORE any disk write;
  keyring add/remove results surfaced to the UI (recheck N07)
- Conservative Keystore encryption gate + typed encrypt failures — a refused
  encryption never leaves a half-written store behind

### Docs
- Root-mode DNS interception path documented (N08); AGENTS.md refresh

## 1.2.0 — external audit response (hardening)

## 1.3.1

- Whitelist refinements: keys the app always enforces itself (`allow-lan`,
  `mixed-port`, `mode`, `log-level`, ports, controller, secret, …) are now
  dropped **silently** — routine panel configs no longer produce scary
  "key removed" warnings; genuinely dangerous drops (listeners, tunnels,
  external-ui, …) still warn
- Refresh snackbar wording: "(пропущено элементов: N)" → "(предупреждений: N)"
- (Fix release-gate pipefail bug shipped in 1.3.0 tag reload)
- Built-in auto-balancer: subscriptions without any balancing group now get a
  synthesized AUTO url-test group (mihomo probes nodes and fails over natively);
  node pool is customizable via exclusions (Settings → Advanced), optional
  "prefer AUTO by default" for panel select-groups; panel balancers always win

## 1.3.0

### Security (external audit wave 1 + 3 — all 24 findings closed)
- Deep-link import now requires explicit confirmation (payload + source + profile name);
  panel behavior headers (auto-select / per-app routing / root-mode switch) apply only after
  an explicit consent dialog; `mihomet://` control links are no longer reachable from browsers
- Core updater: only `mihomet/core` releases, RSA-4096 signed manifest (signature → sha256 →
  size → `mihomo -v` from the new binary), atomic install with backup and rollback
- ConfigGenerator keeps a whitelist of profile keys — `listeners` / `tunnels` / `external-ui`
  from a hostile profile are dropped with visible warnings
- Probe core uses a per-run random secret and an ephemeral port; all HTTP clients release
  their engines (no leaks on settings changes)
- Typed error codes instead of user-facing Russian literals in core/data layers

### Features
- Per-subscription custom HTTP headers (encrypted at rest, editor in the profile card)
- Global subscription identity settings: custom User-Agent, x-hwid on/off + override
- Free-form MTU (1280–65535) and TUN stack selection (gVisor / system / mixed)
- In-app language picker (per-app locales, Android 13+)

### Reliability & build
- Dependencies refreshed (Compose BOM 2026.06, Room 2.8.5, Koin 4.1.1, AGP 8.13.2,
  compileSdk 37 / targetSdk 36); weekly dependency-update CI job
- Per-ABI APK splits (~2× smaller install); shotgun R8 keeps removed and verified by a
  signed-release emulator smoke test (real VPN connect, zero stripped-code crashes)
- Honest ICMP: system `ping` with a distinct "unavailable on this device" state
- God files split (VPN service, settings screen, config converter); typed conversion warnings
- CI: Android Lint + detekt (baselined), emulator matrix (API 29/35) with 14 instrumented tests
- Fixed: YAML editor crashed on every profile (text highlighter offset mapping)

### Hardening (external audit response)
- All P0 findings fixed: Keystore lifecycle, non-root TUN (patched core),
  firewall whitelist semantics, fail-closed profiles, local proxy listener
- Root: readiness gate + full rollback (firewall/Private DNS/OEM chains),
  watchdog stability window; OEM firewall chain snapshots restored
- VPN: TUN-attached readiness gate (connected only when the tunnel is real),
  exit-branch ownership and cleanup, mode-switch serialization
- Firewall: DNS hijack covers TCP:53, policy-rule purge filtered by our fwmark,
  sysctl backup/restore, killall replaced with path-specific matching
- Subscriptions: streamed download with hard byte cap (works without
  Content-Length), semantic profile validation + atomic writes,
  worker refresh validated too
- Honest DNS policy disclosure; updater scope disclosure (root daemon only);
  Happ crypto keyring values encrypted

### Core
- Core releases moved to the dedicated **mihomet/core** repository
  (patched mihomo v1.19.33, Tropa-derived non-root TUN fix); binary retargeted
  to `ANDROID_PLATFORM=android-26` — fixes a silent exit on Android 8–13
- OEM firewall chain save/restore + per-brand setup hints (OnePlus/Realme/ColorOS)

## 1.1.1 — widget & reliability

- Home-screen widget: status + one-tap toggle (RemoteViews, 30-min refresh)
- Connection & memory stats on the dashboard; log export/share
- Battery-optimization prompt (once per install), `allowBypass` and
  HTTP-proxy-through-VPN options

## 1.1.0 — ecosystem compatibility & reliability

### Happ / Incy ecosystem
- One-tap import deep links: `happ://add|routing|crypt`, `incy://add|import|crypt1|onadd|autorouting`,
  `clash://install-config` (+clashmeta/flclash), `v2rayng://install-sub`, `sing-box://import-remote`,
  share-target, and VPN control links (`incy://connect|disconnect|toggle`)
- Honest `Mihomet/` User-Agent (MIHOMO tier on SRR panels) + `x-hwid` device header
- Panel behavior headers applied: `subscription-autoconnect`, `per-app-proxy-mode/list`, `tun-mode`
- Encrypted subscriptions (Happ AES-128-GCM, `encrypt-tag`) with a client-side keyring
- `profile-title`, `New-Url`/`Fallback-Url` auto-migration, `Subscription-Userinfo` quota + expiry
- Background auto-refresh by `profile-update-interval` (WorkManager)

### Core
- Patched mihomo v1.19.33 core (Tropa-derived fix) — non-root TUN no longer fails on
  `/data/system/packages.xml`; core built in CI from the published patch (sha256-pinned)
- Firewall: whitelist semantics fixed (selected apps were going DIRECT), IPv4/IPv6 mark
  symmetry, symmetric teardown; packet-routing simulator tests
- Fail-closed on invalid profiles with last-known-good fallback
- Unauthenticated mixed-port listener removed from the runtime config

### Reliability & security
- Secrets encrypted at rest (AndroidKeyStore AES-256-GCM, Room v5)
- Keystore key lifecycle fixed (reuse, no regeneration per process)
- Core and root-daemon watchdogs with backoff restarts; readiness gate before "connected"
- Always-on VPN handling (`onRevoke`, system restart), screen-on gated speed updates
- Mode switching serialized; core updater rollback prevented

### UI
- Neutral "Layers" brand logo (adaptive icon, themed icons, splash), QR for subscriptions
- RU/EN full localization incl. converter warnings, YAML syntax highlighting
- Advanced overrides: unified-delay, tcp-concurrent, log-level, sniffer
- Autostart on boot, TUN stack/MTU/bypass-LAN settings, live-traffic tweaks

## 1.0.0 — first public release

### Core & security
- Mihomo core v1.19.33 downloaded at build time with pinned sha256 (binaries are not in git)
- Random controller secret generated on first launch; REST API bound to `127.0.0.1` only
- LAN access disabled (`allow-lan: false`), DNS listener on `127.0.0.1`, `allowBackup=false`
- `networkSecurityConfig`: cleartext HTTP forbidden in release (localhost excepted)

### DNS for Russia-restricted networks
- DNS presets classified: plaintext RU / encrypted RU / global (blocked from RU)
- Bootstrap and proxy-server-nameserver pinned to RU-reachable resolvers;
  global DoH choices get an automatic plaintext fallback
- Fixed a tproxy DNS loop: core's own queries bypassed into the tunnel chain

### Subscriptions
- Universal converter: Base64 URI lists, Clash YAML, sing-box JSON, Xray JSON and
  share links — VLESS (Reality/Vision), VMess, SS (incl. SS2022 plain-userinfo),
  Trojan, Hysteria 1/2, TUIC, SSH, Snell, WireGuard; IPv6 hosts
- Fixtures from real panels: Marzban, Marzneshin, 3x-ui, S-UI, Remnawave, Hiddify
  (`PanelsSubscriptionTest` — no node may be lost silently)
- Happ/Incy ecosystem: honest `Mihomet/` UA + `x-hwid` device header,
  `profile-title`, `New-Url`/`Fallback-Url` migration, `Subscription-Userinfo` quota
- Background auto-refresh by `profile-update-interval` (WorkManager, Room v3)
- One-tap import deep links: `happ://`, `incy://`, `clash://`, `clashmeta://`,
  `flclash://`, `v2rayng://`, `sing-box://` and "Share → Mihomet";
  VPN control links (`incy://connect|disconnect|toggle`)
- Non-silent conversion warnings (localized at display time)

### Reliability
- Core watchdog: automatic restart with backoff on crash (VPN + root daemon)
- Always-on VPN: system service restart and `onRevoke` handled
- Root firewall: stale policy-routing rules purged (fixed blackholed DNS)
- Traffic/log WebSockets auto-reconnect; ping concurrency capped

### UI (Material 3 Expressive)
- New brand logo (M-route monogram) with themed/monochrome icon and splash screen
- Full RU/EN localization; live speed in the notification (screen-on gated)
- Subscription quota bar and expiry date; themed snackbars; edge-to-edge
- Advanced overrides: unified-delay, tcp-concurrent, log-level, sniffer
