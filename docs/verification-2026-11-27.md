# Converter and Material 3 Expressive verification — 2026-11-27

This records the converter and interface checks completed before preparing
v1.3.8. The device checks below used app 1.3.7 (13), before the release-only
version bump to 1.3.8 (14). The production core remains
`core-v1.19.33-mihomet.3`; release contents are listed in `CHANGELOG.md`.

## Build and static checks

Use JDK 21 and serialize heavy tasks with the emulator stopped:

```sh
JAVA_HOME=/home/misha/.jdks/jdk-21 ./gradlew \
  compileDebugKotlin testDebugUnitTest detekt assembleDebug \
  assembleDebugAndroidTest assembleRelease lintDebug \
  jacocoUnitTestReport --max-workers=2
```

- The final command succeeded after the system-bar and refresh-race corrections. All 664 JVM
  tests passed without failures, errors or skipped tests. Detekt passed without
  changing its baseline.
- Debug lint completed with no unfiltered errors: 42 warnings and advisory
  baseline hints remain. Neither lint nor detekt baselines were expanded.
- JaCoCo XML and HTML contain actual Kotlin class/line data, including the
  converter; the AGP 9 built-in Kotlin output directory is included.
- All 464 named resource entries match between EN and RU, without duplicate
  names. The six sanitized official XTLS fixture hashes match `sources.json`.
- Each of the three ABI splits in debug and release contains exactly one core;
  its SHA-256 and byte size match `gradle/mihomo-core.properties`.

Gradle uses two workers and in-process Kotlin compilation. Its daemon was
stopped before device testing; emulators ran one at a time with 1536 MB RAM and
two cores. No production signing key or elevated host access was needed.

## Android integration and real connections

API 26 and API 35 instrumentation both completed with `OK (26 tests)`:
25 mandatory cases passed, while the optional wire case was assumption-skipped
when no fixture server was supplied. The mandatory cases include:

- real HTTP UA selection, less lossy response replacement and matching metadata;
- delayed refreshes preserving current selection, headers, overrides, quota and
  manually edited intervals, without recreating a deleted profile or its file;
- validated encrypted profile persistence and Room migrations through v6;
- VIEW import navigation, confirmation/cancellation and queued imports;
- updating existing WorkManager refresh work to an hourly schedule;
- native bundled-core `-t` checks of converted official and synthetic fixtures;
- a temporary core probe without root or an active VPN.

The optional wire case was then run explicitly against isolated local Xray
26.10.31 and 25.3.6, on both Android versions:

```sh
python3 scripts/test-xray-loopback.py --serial emulator-5554 \
  --adb /home/misha/Android/Sdk/platform-tools/adb --xray /path/to/xray
```

Each run covers 16 named connections: VLESS TCP/WS/gRPC/XHTTP, separate XHTTP
download streams, VMess WS, Trojan TLS, authenticated flat outbounds,
HTTPUpgrade and Reality from JSON and URI. The host HTTP server verifies that
every named request arrived. These checks do not contact public proxies.
All four wire runs used the final rebuilt debug APK after the refresh-race
correction; they do not rely on class-file equivalence to an older build.

Both refresh races were reproduced with delayed MockWebServer responses and a
real Room database before the fix. Refresh now reads and updates the current
row inside a transaction, together with atomic file replacement. Profile deletion
removes the row before its file, so a pending response cannot recreate either.
The two new regression tests passed on both Android versions.

## Interface

API 35 renders were inspected for light, dark and neutral AMOLED themes;
populated profiles/servers; persistent reports; settings details, routing, site
rules, connections, logs and the YAML editor. RU import was checked with the
actual soft keyboard. At 320 dp and 150% font scale, navigation labels stay
within their bounds and retain their full accessibility text.

The final R8 APK was also rendered on API 26 and API 35 after the inactive-card
and system-bar corrections. Selected and unselected profile/server cards were
inspected, along with light/dark/AMOLED settings. Navigation-bar backgrounds and
system icon colors now follow the app's theme on Android 8 as well.

## Release smoke

The first API 26 import into the R8 release reproduced a SnakeYAML
`ExceptionInInitializerError`: its logger initializer called
`Class.getPackage().getName()` after repackaging into the default package.
Two narrow `-keepnames` rules preserve names for `TypeDescription` and
`PropertySubstitute`; shrinking and optimization remain enabled.

The unsigned release is tested by signing a separate copy with the local
Android debug key. Production signing configuration is unchanged.
The final APK passed URL import, manual remote-profile refresh through the new
Room transaction, shared two-node Xray JSON import, report inspection,
profile/node selection and VPN start/stop checks on API 26 and API 35.
Both versions reopened the saved report after a cold restart. Native core
processes and `tun0` were observed while protected; both shutdown checks confirmed
that the native process exited and `tun0` disappeared before any force-stop.
The application crash buffers contained no application crash.

## Final result matrix

| Check on the final APKs | API 26 | API 35 |
|---|---|---|
| Mandatory instrumented cases | 25 passed | 25 passed |
| Optional wire case without fixture | Assumption-skipped | Assumption-skipped |
| Wire cases with Xray 26.10.31 | 16/16 passed | 16/16 passed |
| Wire cases with Xray 25.3.6 | 16/16 passed | 16/16 passed |
| R8 import, manual refresh and persisted report | Passed | Passed |
| Native VPN start and clean stop | Passed | Passed |
| Light/dark/AMOLED selection-card and system-bar renders | Inspected | Inspected |

The final build log is `/tmp/mihomet-refresh-final-gates.log`. Device logs are
`/tmp/mihomet-refresh-final-api{26,35}-instrumentation.log`,
`/tmp/mihomet-refresh-final-wire{25,26}-api{26,35}.log` and
`/tmp/mihomet-refresh-final-release-api{26,35}.log`.
Final release screenshot montages are
`/tmp/mihomet-refresh-final-release-api{26,35}-montage.png`.
These local evidence files are not release artifacts or tracked source files.

## Limits of this verification

The device checks use x86_64 emulators. Physical ARM devices and root/TProxy
hardware have not been certified by this pass. Existing root/firewall/service
code is unchanged; their unit and configuration checks still pass.

The API 35 emulator displayed a System UI ANR during its first cold-boot launch.
Choosing Wait recovered it; the release checks were restarted and completed
after recovery.

The scope is Xray/Happ portability against the pinned Mihomo core. Broader
sing-box conversion remains deferred. Unsupported transports/security checks,
nonportable rules and balancer approximations are reported; a configuration
check or these wire cases do not establish universal cross-core equivalence.
