# Mihomet — AI Agent Developer Guide

> **Brief & Actionable Reference for AI Coding Agents working on the Mihomet codebase.**

---

## 1. Project Overview & Capabilities
**Mihomet** is a high-performance Android client for the **Mihomo** (`Clash.Meta` v1.19+) core.
- **Dual Execution Modes**:
  1. **Non-Root Mode**: Uses Android `VpnService` (TUN interface + Tun2Socks stack).
  2. **Root TProxy Mode**: Uses `su` root shell and `iptables` / `nftables` transparent proxying.
- **Target OS**: Android 10 (API 29) to Android 15/16 (API 35+).
- **Primary Language**: Kotlin 2.0+ with modern Coroutines and Flows.
- **UI Toolkit**: Jetpack Compose with **Material 3 Expressive** design system.
- **Localization**: UI text is exclusively in **Russian**. Keep all UI copy, dialogs, and errors in Russian.

---

## 2. Architecture & Directory Map

```text
app/src/main/kotlin/org/mihomet/app/
├── core/                        # Core Engine & Operating System Interop
│   ├── CoreManager.kt           # Native libmihomo.so lifecycle, process runner, temp test probes
│   ├── ServiceState.kt          # Global reactive state (isRunning, runningService: VPN | ROOT)
│   ├── config/                  # Config generation (Yaml generator, UniversalConfigConverter)
│   ├── network/NodePingTester   # Direct socket-level TCP, TLS, and ICMP latency tester
│   ├── root/                    # RootShellManager, RootTProxyService (iptables/nftables)
│   └── vpn/                     # MihometVpnService (Android VpnService, TUN fd, notification)
├── data/                        # Data Layer
│   ├── api/MihomoApiClient.kt   # Mihomo REST/WebSocket API client (localhost:9090)
│   ├── db/                      # Room Database (AppDatabase, ProfileDao, ProfileEntity)
│   └── preferences/             # DataStore Preferences (SettingsRepository, AppSettings)
├── di/                          # Dependency Injection via Koin
│   └── AppModule.kt             # ViewModels, Repositories, DAOs, ApiClient singletons
└── ui/                          # Presentation Layer (Jetpack Compose M3 Expressive)
    ├── components/              # Reusable M3 Expressive widgets (NavigationBar, Badges, Cards)
    ├── navigation/              # NavGraph.kt (Shared-axis lateral animations), Screen.kt
    ├── screens/                 # Dashboard, Proxies, Profiles, Routing, Settings, Logs, Connections
    └── theme/                   # Theme.kt, Color.kt, Shape.kt, AppHaptics.kt
```

---

## 3. Key Subsystems & How They Work

### Native Mihomo Core (`core/CoreManager.kt`)
- Stored as `libmihomo.so` inside APK ABI directories (`lib/arm64-v8a`, `armeabi-v7a`, `x86_64`).
- Extracted to app's internal files directory on boot.
- Controlled via REST API (`http://127.0.0.1:9090` by default) with secret token.
- `runTemporaryCoreProbe()`: Runs a lightweight ephemeral instance to test proxy latency when the main VPN core is offline.

### Connection Services
- `MihometVpnService`: Standard Android VPN builder. Handles system VPN permission, bypasses localhost controller, routes traffic into TUN.
- `RootTProxyService`: Runs via `RootShellManager`. Sets up iptables prerouting, route tables, and bypasses local daemon UIDs.

### UI Guidelines (Material 3 Expressive)
- Use `ExpressiveShapes` (`Hero` = 38.dp, `Large` = 26.dp, `Small` = 12.dp, `Pill` = CircleShape).
- For inactive states, avoid raw saturated colors; use `MaterialTheme.colorScheme.surfaceContainerHighest` with `outlineVariant` border.
- For AMOLED themes, keep `background = #000000` while preserving container elevation steps (`#16161F` -> `#1E1E28` -> `#282836` -> `#343444`).
- Navigation transitions must use directional shared-axis slide + fade (`FastOutSlowInEasing`).

---

## 4. Verification & Build Commands

Always run these Gradle tasks before finishing any task:

```bash
# 1. Check Kotlin compilation
./gradlew compileDebugKotlin

# 2. Run unit test suite (28+ tests)
./gradlew testDebugUnitTest

# 3. Assemble Debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# 4. Assemble Signed Release APK
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk

# 5. Install on target device via ADB (without running unauthorized UI tests)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. Important Invariants
1. **Never break non-root mode**: The app must run cleanly without root permissions for standard users.
2. **Never break root mode**: TProxy rules and `RootShellManager` commands must retain root fallback.
3. **No disruptive snackbars**: Core stop/start states are indicated through the hero button and badges, not pop-up snackbars.
4. **Subscription custom headers**: Accept both multi-line HTTP headers (`Header-Name: value`) and JSON. Keep `minLines = 1, maxLines = 5` with vertical scroll.
5. **Russian UI strings**: Do not replace Russian UI text with English unless explicitly requested.
