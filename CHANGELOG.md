# Changelog

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
