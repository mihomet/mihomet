# Changelog

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
- Home-screen widget (status + toggle), connection/memory stats, log export/share
- Battery-optimization prompt, allowBypass and HTTP-proxy-through-VPN options

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
