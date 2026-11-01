---
name: android-mobile-dev
description: >-
  Advanced Android development guidelines and patterns. Use when working on Android architecture,
  VpnService, Foreground Services, Android 8.0-16 (API 26-37) compatibility, Room database & migrations,
  DataStore, AndroidKeyStore crypto, netfilter (iptables/nftables) & root shell (libsu), NetworkCallback,
  and per-ABI packaging.
---

# Advanced Android Development Guidelines

This skill covers system interop, lifecycle management, performance, and security for modern Android applications (minSdk 26 through targetSdk 36 / compileSdk 37).

## 1. Foreground Services & Lifecycle Management

- **API Compatibility**:
  - Android 14+ (API 34+): Specify explicit foreground service types in `AndroidManifest.xml` (e.g., `systemExempted`, `specialUse`).
  - Pass the corresponding `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` flag in `startForeground()`.
  - Android 8.0+ (API 26+): Use `startForegroundService()` from background/receivers.
  - Set `NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE` to eliminate the default 10-second notification delay.
- **Fail-Closed Teardown**:
  - Always clean up state before calling `stopSelf()`.
  - Handle `onRevoke()` for VpnService cleanly to avoid leaving orphan processes.

## 2. VpnService & Network Routing

- **TUN Interface Configuration**:
  - MTU: Default to 9000 for gVisor/Mihomo; allow user customization within 1280–65535.
  - Split Tunneling (Per-App): Use `addAllowedApplication(pkg)` or `addDisallowedApplication(pkg)`.
  - Always disallow the app's own package (`addDisallowedApplication(packageName)`) to avoid routing loops.
  - Bypass-LAN: Android VpnService routes are additive. To bypass LAN (10/8, 172.16/12, 192.168/16), route the complement of the private address space into the tunnel.
- **Roaming & Network Callbacks**:
  - Register `ConnectivityManager.NetworkCallback` with `NET_CAPABILITY_NOT_VPN` and `NET_CAPABILITY_INTERNET`.
  - Call `VpnService.setUnderlyingNetworks()` on active network change and flush stale sockets.

## 3. Storage & Cryptography

- **Room Database**:
  - Always provide deterministic, non-destructive `Migration` classes.
  - Enable `exportSchema = true` and track schema JSON files in version control.
- **AndroidKeyStore (AES-256-GCM)**:
  - Encrypt sensitive data (tokens, subscription URLs) at rest with an `enc1:` payload marker.
  - Implement a conservative data-loss gate: never overwrite or recreate an existing key alias if encrypted data is detected on disk.

## 4. Root & Netfilter Interop

- **Root Shell (`libsu`)**:
  - Validate exit codes per command.
  - Use atomic operations (`tmp` file write + `mv`) when updating configs and system settings.
  - Save and restore OEM firewall chains (e.g., ColorOS/MIUI DNS reject chains).
  - TProxy: Apply policy routing marks (`ip rule add fwmark <mark> lookup <table>`) and clean them up cleanly on exit.

## 5. App Packaging & Security

- Use ABI splits (`arm64-v8a`, `armeabi-v7a`, `x86_64`) to minimize APK download size.
- Replace broad permissions like `QUERY_ALL_PACKAGES` with scoped `<queries>` (e.g., `MAIN`/`LAUNCHER` intents).
- Use `network_security_config.xml` to disallow cleartext HTTP traffic in release builds, restricting cleartext strictly to loopback (`127.0.0.1`/`localhost`).
