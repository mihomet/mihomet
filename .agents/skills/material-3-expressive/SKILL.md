---
name: material-3-expressive
description: >-
  Expert guidance for Jetpack Compose UI with Material 3 Expressive. Use when designing, building,
  or reviewing Compose UI components, screens, animations, shapes, color schemes, themes (including
  Pure Black AMOLED), haptics, motion transitions (Shared Axis), and Expressive components
  (ExpressiveNavigationBar, Pills, Hero cards, Pulsing badges).
---

# Material 3 Expressive in Jetpack Compose

This skill provides guidelines and patterns for implementing modern Material 3 Expressive UI in Jetpack Compose, emphasizing visual rhythm, tactile feedback, kinematic motion, and optimal OLED efficiency.

## 1. Shapes and Geometry (Expressive Shapes)

Avoid default generic shapes. Use distinct expressive tokens:
- **Hero**: `RoundedCornerShape(38.dp)` — used for hero elements, action buttons, and focal cards.
- **Large**: `RoundedCornerShape(26.dp)` — used for cards, bottom sheet dialogs, and large interactive containers.
- **Medium**: `RoundedCornerShape(18.dp)` — used for secondary cards and dialogs.
- **Small**: `RoundedCornerShape(12.dp)` — used for chips, text fields, and segmented pills.
- **Pill / Circle**: `CircleShape` — used for status badges, search bars, and floating controls.

Inactive/elevated surfaces:
- Use `MaterialTheme.colorScheme.surfaceContainerHighest` paired with `BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))`.

## 2. Color Palettes and True AMOLED

- **AMOLED Pure Carbon Theme**:
  - `background = Color(0xFF000000)` (pure black, zero OLED pixel power).
  - Neutral graphite containers (`R == G == B`), e.g., `Color(0xFF161616)` or `Color(0xFF222222)`, strictly without purple or color tints.
  - High-contrast text: `Color(0xFFEDEDED)` / `Color(0xFFB0B0B0)`.
- **Accent Tokens**:
  - Primary violet (`#8B5CF6`, light variant `#A78BFA`).
  - Secondary teal/cyan (`#2DD4BF`).
  - Tertiary pink/rose (`#F472B6`).
  - Success green (`#10B981`) and Warning amber (`#F59E0B`).

## 3. Motion & Animations (Shared Axis)

- Use directional **Shared-Axis** slide + fade transitions for horizontal navigation:
  ```kotlin
  slideInHorizontally(
      initialOffsetX = { fullWidth -> if (forward) fullWidth / 4 else -fullWidth / 4 },
      animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
  ) + fadeIn(animationSpec = tween(durationMillis = 250))
  ```
- **Interactive State Animations**:
  - Scale effects: `animateFloatAsState` on tap/press.
  - Spin-down / contracting halo for disconnect and transition states.

## 4. Tactile Feedback (Haptics)

- Provide tactile feedback on user gestures using `LocalHapticFeedback.current`:
  - `HapticFeedbackType.TextHandleMove` for light feedback on toggles and tabs.
  - `HapticFeedbackType.LongPress` on destructive or mode-switching actions.

## 5. Compose Performance & Best Practices

- Use `@Stable` and `@Immutable` data holders for UI states.
- Avoid passing raw ViewModel to leaf components; pass plain data and lambdas (`onAction: () -> Unit`).
- Use `derivedStateOf` for values computed from fast-changing states (e.g., scroll offsets).
- Never trigger network or disk I/O in composable bodies without `LaunchedEffect(key)`.
