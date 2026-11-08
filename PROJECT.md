# Project: mihomet Mobile VPN — PixelPlayer Material 3 Expressive Figma Redesign

## Architecture & Visual Foundation
- **Target File**: `q9LrjY0h4Z46sM0rUWHTSc` ("Untitled", Page 1 `0:1`)
- **Baseline Container**: `4:354: Frame` (1600x840 at `[0, 0]`) containing original screens `4:355`, `4:434`, `4:522`, `4:588` (preserved intact).
- **Redesign Container**: `Frame-PixelPlayer-Redesign` (1600x840 at `[0, 920]`) on Page 1 `0:1`.
- **Screen Geometry**: 4 mobile screen frames, each `360 x 800`, spaced with 40px horizontal gutters and 20px padding.
- **Design System Language**: Authentic PixelPlayer (`PixelPlayerHQ/PixelPlayerOSS`) and Material 3 Expressive (M3E Edition 2).
  - Background: Pure Black AMOLED `#000000` (true zero subpixel power draw).
  - Surface Tiers: Neutral graphite without purple tint:
    - `surfaceContainerLow`: `#080808`
    - `surfaceContainer`: `#101010`
    - `surfaceContainerHigh`: `#181818`
    - `surfaceContainerHighest`: `#242424`
  - Radii & Superellipses:
    - Hero elements: `38dp`
    - Large containers: `28dp`
    - Standard panels: `24dp`
    - Inner squircles: `16dp`
    - Connected list items: Asymmetric grouped shape (first: top 24dp / bottom 4dp; middle: 4dp; last: top 4dp / bottom 24dp; single: 24dp; 2dp gap)
    - Pills & Chips: `50dp` / `CircleShape`
  - Typography: Google Sans Flex / Roboto Flex proportions with bold weight, negative tracking (-0.02em), and tight leading.
  - Zero Blurry AI Drop Shadows: Tonal elevation via luminance steps and 1dp subtle borders (`#262626`).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Redesign Container Frame & Foundations | Create parent `Frame-PixelPlayer-Redesign` at [0, 920, 1600, 840] with base styling tokens | M1 | Survey (Figma & PixelPlayer) |
| 2 | Dashboard — Hero Tactile Connect Switch | Cookie-12 morphing power button (132x132, #10B981) with dual kinetic halo rings | M2 | Survey (mihomet & PixelPlayer) |
| 3 | Dashboard — Status & Session Readout | Display title ("PROTECTED"), session uptime pill ("VPN · 01:24:12") | M2 | Survey (mihomet) |
| 4 | Dashboard — Active Server Strip | Tonal container (#181818, 26dp) with flag squircle, server name, ping pill ("24 ms") | M2 | Survey (mihomet) |
| 5 | Dashboard — Live Traffic Stat Blocks | Dual Download/Upload blocks (#10B981 / #06B6D4) with sparkline bezier curves | M2 | Survey (mihomet) |
| 6 | Dashboard — Routing Mode Segmented Pills | 3-way segment control (Smart/Rule, Global, Direct) in stadium pill track | M2 | Survey (mihomet) |
| 7 | Dashboard — Floating M3E Nav Bar | Detached stadium capsule (324x66) with morphing pill indicator on Dashboard | M2 | Survey (PixelPlayer & mihomet) |
| 8 | Servers — Header & Fast Ping Action | Screen title ("Servers"), count badge ("64"), test all nodes quick action | M3 | Survey (mihomet) |
| 9 | Servers — Search Pill & Sort Chip | Neutral graphite search capsule (#181818) with "FASTEST" sorting chip | M3 | Survey (mihomet) |
| 10 | Servers — Filter Tabs | Horizontal scrolling filter pills (PROXIES, AUTO, FALLBACK, DIRECT) | M3 | Survey (mihomet) |
| 11 | Servers — Asymmetric Grouped Server Cards | 4 server rows with 24dp/4dp asymmetric shapes, country squircle, protocol tags, latency pills | M3 | Survey (PixelPlayer & mihomet) |
| 12 | Servers — Selection Indicator | Animated primary container fill with checkmark icon for active node | M3 | Survey (mihomet) |
| 13 | Servers — Floating M3E Nav Bar | Detached capsule with Servers tab active indicator pill | M3 | Survey (mihomet) |
| 14 | Subscriptions — Header & Add Action | Screen title ("Subscriptions"), count pill, circular FAB with Add icon | M4 | Survey (mihomet) |
| 15 | Subscriptions — Import Action Card | Dashed container (#181818) with plus icon ("Import via URL / Clipboard") | M4 | Survey (mihomet) |
| 16 | Subscriptions — Active Plan Card | Tonal panel with cloud icon, plan title ("FastPremium VIP"), auto-update status | M4 | Survey (mihomet) |
| 17 | Subscriptions — Traffic Quota Capsule | Dual metric ("48.5 GB / 120 GB"), stadium progress bar with violet fill | M4 | Survey (mihomet) |
| 18 | Subscriptions — Expiry Pill & Actions | Amber warning pill ("14 days left"), Update / Edit action buttons | M4 | Survey (mihomet) |
| 19 | Subscriptions — Floating M3E Nav Bar | Detached capsule with Profiles/Subscriptions tab active indicator pill | M4 | Survey (mihomet) |
| 20 | Settings — Grouped Appearance Section | 24dp/4dp grouped container: "AMOLED Pure Black" toggle (ON) & "Material You" toggle (OFF) | M5 | Survey (mihomet & PixelPlayer) |
| 21 | Settings — Grouped Connection Section | 24dp/4dp grouped container: "Working Mode" chevron, "Bypass LAN" toggle, "IPv6 Route" | M5 | Survey (mihomet) |
| 22 | Settings — Grouped System & Core Section | 24dp/4dp grouped container: "Mihomo Core Alpha" chevron, "About Mihomet" (MD3E Edition 2) | M5 | Survey (mihomet) |
| 23 | Settings — Floating M3E Nav Bar | Detached capsule with Settings tab active indicator pill | M5 | Survey (mihomet) |
| 24 | Holistic Visual & Integrity Verification | Objective review, adversarial challenge (anti-AI tropes), and forensic node audit | M6 | Survey & Acceptance Criteria |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Figma Workspace & Base Layout Setup | Create `Frame-PixelPlayer-Redesign` at [0, 920, 1600, 840] with base styling | none | PLANNED |
| M2 | Dashboard Screen Redesign | Create `Redesign-Screen-Dashboard` at [20, 940] with M3E hero switch & traffic | M1 | PLANNED |
| M3 | Servers Screen Redesign | Create `Redesign-Screen-Servers` at [420, 940] with 24/4dp grouped cards | M1 | PLANNED |
| M4 | Subscriptions Screen Redesign | Create `Redesign-Screen-Subscriptions` at [820, 940] with quota & cards | M1 | PLANNED |
| M5 | Settings Screen Redesign | Create `Redesign-Screen-Settings` at [1220, 940] with expressive toggles | M1 | PLANNED |
| M6 | Review, Adversarial Challenge & Forensic Audit | Complete review, challenger verification, and forensic audit across all 4 screens | M2, M3, M4, M5 | PLANNED |

## Code & Design Layout
- Figma File: `q9LrjY0h4Z46sM0rUWHTSc`
- Original Baseline: Frame `4:354` at `[0, 0, 1600, 840]`
- Redesign Container: `Frame-PixelPlayer-Redesign` at `[0, 920, 1600, 840]`
- Screen 1: `Redesign-Screen-Dashboard` (360x800) at `x=20, y=940` (local `x=20, y=20`)
- Screen 2: `Redesign-Screen-Servers` (360x800) at `x=420, y=940` (local `x=420, y=20`)
- Screen 3: `Redesign-Screen-Subscriptions` (360x800) at `x=820, y=940` (local `x=820, y=20`)
- Screen 4: `Redesign-Screen-Settings` (360x800) at `x=1220, y=940` (local `x=1220, y=20`)
- Shared Navigation Component: Detached stadium capsule (324x66 at `y=732`), 4 items with spring pill indicators.
