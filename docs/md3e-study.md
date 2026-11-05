# M3 Expressive study — PixelPlayer patterns in Mihomet

Study notes from the "real MD3E" redesign task (06.11). Source of truth:
[PixelPlayerHQ/PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS)
(GPL-3.0, the only variant we may port code from) and
[theovilardo/PixelPlayer](https://github.com/theovilardo/PixelPlayer)
(proPRIETARY — study only, never copy).

## Version gate (important)

- material3 **1.5.x** (full Expressive components: LoadingIndicator, FloatingToolbar,
  FlexibleBottomSheet, ButtonGroup, SplitButton, wavy progress, MaterialShapes,
  public `MotionScheme.expressive()`) declares
  `minAndroidGradlePluginVersion=9.1.0` and drags compose **1.12.0-alpha**
  transitives. Our AGP is 8.13.2 → `checkDebugAarMetadata` fails. There is no
  suppress flag for the AGP part.
- material3 **1.4.0** (our BOM) ships `MotionScheme`/`MaterialExpressiveTheme`
  as **internal** — the expressive motionScheme parameter is unusable there.
- Decision: stay on 1.4.0 + standalone `androidx.graphics:graphics-shapes:1.1.0`
  (AGP 8.1.1) and hand-build the signature pieces. Revisit everything with the
  AGP 9.1 / Kotlin upgrade (see TODO.md).

## What shipped in Mihomet

| Pattern (PixelPlayer) | Our implementation |
|---|---|
| Cookie-morph hero (`ShapeCache`, MaterialShapes) | `ui/theme/ExpressiveMorph.kt` — Square↔Cookie12 via cached `Morph`, per-frame fitted path on `graphicsLayer{shape}` (Surface/clip cannot morph). Polygon gotcha: `rectangle(w,h)` spans `-w/2..w/2`; corner radius is absolute → side 2 + radius 0.62, otherwise it clamps to a circle. |
| Asymmetric parallax push (Transitions.kt) | `ui/navigation/Transitions.kt` — detail enter: slide 100% + scale 0.92 on EmphasizedDecelerate; exit: parallax −25% + fade at HALF duration on EmphasizedAccelerate; tabs: 50% parallax, no scale; pop mirrors. |
| Bounce navbar pill | already in `ExpressiveNavigationBar` (spring pill scale + color). |
| Sheet-first forms | `AddProfileDialog` → `ModalBottomSheet` (1.4.0 has no FlexibleBottomSheet; param is `skipPartiallyExpanded`, not `skipHiddenState`). |
| Palette styles from a seed | material-kolor 2.1.1 (MIT): `dynamicColorScheme(seed, isDark, isAmoled, style)` — whole scheme from the accent tone; accent picker finally wired to UI (Settings → Appearance); system dynamic colors keep precedence. |

## Deferred (until AGP 9.1)

LoadingIndicator / wavy progress / FloatingToolbar / ButtonGroup /
FlexibleBottomSheet / public MotionScheme; ScreenWrapper depth (blur+dim on the
covered screen — needs AnimatedContentScope per destination, RenderNode cost).
