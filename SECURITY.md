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

- Mihomet bundles the [mihomo](https://github.com/MetaCubeX/mihomo) core
  binary. Its integrity is enforced at build time via pinned sha256 checksums
  (`gradle/mihomo-core.properties`); the binary is downloaded only from the
  official MetaCubeX GitHub releases.
- The controller REST API listens on `127.0.0.1` with a per-install random
  secret; LAN access is disabled by default.
