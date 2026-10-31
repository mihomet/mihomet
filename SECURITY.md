# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| latest release | ✅ |
| older releases | ❌ |

## Reporting a Vulnerability

Please report security issues privately via **GitHub Security Advisories**
(Security → Report a vulnerability) rather than public issues.

Include:
- affected version / commit,
- steps or PoC to reproduce,
- impact assessment.

You can expect an initial response within **7 days**. Please do not test
against devices you do not own.

## Notes

### Bundled core

- Mihomet does **not** ship the stock [MetaCubeX mihomo](https://github.com/MetaCubeX/mihomo)
  binary. It bundles the **patched build published at
  [github.com/mihomet/core](https://github.com/mihomet/core/releases)**: mihomo
  v1.19.33 with a non-root TUN fix (patch idea: [Tropa](https://github.com/Tropa-App), GPL-3.0 —
  stock core fails reading `/data/system/packages.xml` without root).
- **GPL-3.0 source offer:** the complete corresponding source of the bundled GPL
  core is the `mihomet/core` repository (patch + reproducible build script).
- **Build-time integrity:** the exact release is pinned in
  `gradle/mihomo-core.properties`; the Gradle `downloadMihomoCore` task verifies
  the sha256 (and byte size) of every ABI before the binary enters the APK. CI
  additionally re-checks the sha256 of the `.so` packed into the built APK.
- **Runtime update integrity:** the in-app core updater downloads ONLY from
  `github.com/mihomet/core/releases` (never MetaCubeX) and installs a release
  only when the full chain passes: RSA-4096 `SHA256withRSA` signature over
  `manifest.json` (public key embedded in the app) → manifest version/tag/ABI
  sanity → sha256 + exact byte size of the downloaded `.so` → the new binary is
  executed (`mihomo -v`) under the root shell **before** the installed one is
  touched → atomic replace with the previous binary kept as backup and restored
  on any failure. Releases without a valid signed manifest are refused.
- The temporary probe core (offline node delay testing) uses a **per-run random
  controller secret** and a **free ephemeral port** — no static credentials are
  baked into the APK.

### Runtime surface

- The controller REST API listens on `127.0.0.1` with a **per-install random
  secret** (generated at first launch, `SettingsRepository`); LAN access is
  disabled by default (`allow-lan: false`, `bind-address: 127.0.0.1`).
- External deep-link imports (happ://, clash://, incy://, …) are **never**
  applied silently: every import shows a confirmation dialog (payload + source +
  target profile name), and panel behavior headers (including root/VPN mode
  switching) apply only after explicit user consent.
- Control links (`mihomet://start|stop|toggle`) are not reachable from browsers
  (no BROWSABLE intent filter) and are accepted only from the app's own
  PendingIntents.
- User profile YAML is filtered through a top-level key whitelist before it
  reaches the core: panel-supplied `listeners`, `tunnels`, `external-ui`,
  `geox-url`, etc. are dropped with a user-visible warning.
