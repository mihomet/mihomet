# Analytics

Mihomet analytics is optional and disabled by default. This integration is in
`Unreleased`; the published v1.3.8 does not include it. It uses the Aptabase
Kotlin SDK only after the user enables the setting.

## Build configuration

Release CI and the signed test APK workflow pass the optional `APTABASE_APP_KEY` repository secret to the
Android build. The key is not stored in the repository. The app identifier is
embedded in a configured APK and is not a server secret. No live key is included
in the default build. Configure the optional repository secret after creating an
Aptabase app; locally, pass `-PAPTABASE_APP_KEY=A-EU-<app-id>` or the same environment
variable to Gradle. Replace `<app-id>` with the real app ID. A Gradle property
takes precedence over the environment variable, including when the property is
empty. Changing the key requires rebuilding the APK; setting the repository
secret affects subsequent workflow builds and does not opt users in.

Keys are trimmed and must match `A-(US|EU)-[A-Za-z0-9_]+`: the app ID must be
nonempty and cannot contain hyphens. This checks the key format, not whether the
app is registered with Aptabase. The app does not set `InitOptions.host` and
rejects self-hosted `A-SH-*` keys.

When the key is missing or fails this structural validation:

- the analytics switch is hidden;
- Aptabase is not initialized;
- no analytics network request is made.

## Events

The settings switch is off by default, with a separate disclosure dialog listing the collected metadata.

After opt-in, the tracker attempts to send these events; delivery is best-effort:

- `app_started`, at most once per app process and with no custom properties;
- `conversion_completed`, at most 50 events per app process, with these bounded custom properties:
  - `format`: `clash-yaml`, `happ-routing`, `sing-box`, `uri-list`,
    `wireguard`, `xray`, or `unknown`;
  - `success`: the string `success` or `failure`, based on conversion validity;
  - `warning_count`: an integer clamped to 0–100 from the converter warning count.

The conversion `format` comes from `ConversionOutcome.format`. Tracking covers
local imports and remote import/manual/background refresh conversion paths,
without counting each User-Agent fallback attempt. A network or decryption
failure that produces no converter outcome does not produce a conversion event.
`success` is evaluated before profile persistence, so a successful conversion can
still be followed by a failed import/save. Conversion source content is not included.

## SDK metadata

The Kotlin SDK adds a timestamp, session ID, event name, debug flag, Android/OS
name and version, language, app version/build number, SDK version, and device model.
Aptabase processes the request IP address and User-Agent transiently in memory to
derive a daily rotating pseudonymous identifier and country/region. The IP address
and User-Agent are not stored; the derived country/region and event data are retained
for up to five years in the US or EU region selected by the app key. These are server
metadata, not custom properties from Mihomet. The in-app disclosure lists these data.

The app does not send source payloads, subscription URLs, profiles,
configurations, credentials, secrets, or conversion error text.

## Disabling

When the user changes the analytics setting, the app closes or opens its consent gate
synchronously before writing to DataStore. Stale DataStore emissions that conflict with
the latest user choice are ignored until the stored value catches up. Turning analytics
off blocks new SDK handoffs and cancels pending tracking jobs. Events captured without
consent are dropped; delayed events from
a previous consent period are rejected even after re-enabling. The Aptabase SDK
version used by the app has no public disable, flush, clear, or
request-cancellation API. A
request already handed to the SDK cannot be recalled; the app does not promise
revocation of an event that was already handed off.

Re-enabling analytics in the same process does not send another `app_started`
event.

## SDK reference

The dependency is pinned to Aptabase Kotlin 0.1.0. Its published metadata
declares an unused `androidx.test:monitor` runtime dependency. The app excludes
that dependency so test classes do not enter application APKs or shadow the
monitor selected by the instrumentation test dependencies.

Reviewed primary sources:
[SDK API](https://github.com/aptabase/aptabase-kotlin/blob/0.1.0/aptabase/src/main/java/com/aptabase/Aptabase.kt),
[environment metadata](https://github.com/aptabase/aptabase-kotlin/blob/0.1.0/aptabase/src/main/java/com/aptabase/EnvironmentInfo.kt),
[Aptabase privacy policy](https://aptabase.com/legal/privacy), and its
[data-processing agreement](https://aptabase.io/legal/dpa). The privacy disclosure
was checked against the current policy on 2026-11-28.
