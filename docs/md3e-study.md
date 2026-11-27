# M3 Expressive study — PixelPlayer patterns in Mihomet

Reference: [PixelPlayerHQ/PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS/tree/1664e8e636b57655a4f47dc9f3325b081cb02fc9)
at `1664e8e636b57655a4f47dc9f3325b081cb02fc9` (GPL-3.0). The implementation uses
Mihomet's own controls, content and navigation. No code from the proprietary
PixelPlayer variant is incorporated.

## Build gate resolved

The project now uses AGP 9.1.0 / Gradle 9.3.1, built-in Kotlin 2.3.20, Compose
BOM 2026.09.00 and Material 3 1.5.0. The former Material 3 1.4 restrictions no
longer justify imitating components that are available natively.

## Implemented patterns

| Pattern | Mihomet implementation |
|---|---|
| Expressive theme and motion | `MaterialExpressiveTheme`, `MotionScheme.expressive()`, shared-axis destination transitions |
| Wallpaper colors | Material You by default only when the stored preference is absent; existing preferences remain authoritative |
| AMOLED | Black background and neutral graphite surface containers; selected accent colors remain available to controls |
| Navigation | Native `ShortNavigationBar` / `ShortNavigationBarItem` in the shared rounded shell |
| Hero | Cached square/cookie morph without a shadow on the concave path; native `LoadingIndicator` for transitions |
| Routing mode | Native expressive `ButtonGroup` |
| Server actions | Native `HorizontalFloatingToolbar` and shared connected rows |
| Profile actions | Named FAB, native list content, delete confirmation and a persistent conversion report sheet |
| Inactive selection cards | `surfaceContainerHighest` and `outlineVariant` border, as required by the project guide |
| Settings | Six categories with detail pages and system Back handling, shared titles and row geometry |
| Insets and lifecycle | Root shell owns system insets; screen flows use lifecycle-aware collection |

Root and detail screens have been rendered on API 35, including RU, the soft
keyboard and 320 dp width at 150% font scale. The final R8 APK was checked on API
26 and API 35 for selection cards, reports, theme changes and matching system
bars. Results and hardware limits are recorded in
[the verification report](verification-2026-11-27.md). Expensive screen-wide blur
remains a separate deferred feature.
