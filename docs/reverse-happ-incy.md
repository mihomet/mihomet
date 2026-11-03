# Reverse-engineering notes: Happ 4.9.0 and Incy 3.9.8 (2026-11-02/03)

*Russian version: [reverse-happ-incy.ru.md](reverse-happ-incy.ru.md)*

Static analysis of the official release APKs (public GitHub releases). The goal is to
compare the ecosystem clients' implementation against Mihomet and catch compatibility
gaps. Tool: androguard (manifest + DEX strings), no decompilation to Java.

- Happ: `Happ-proxy/happ-android` release 4.9.0, `Happ.apk`, sha256
  `47aac2a2310553ca85ef390c2ae1674d79d9d70c215ef36a35cdf433f5b8323d`
- Incy: `INCY-DEV/incy-platforms` release android-v3.9.8, `Incy.apk`, sha256
  `235d433346fb2c6371133d9952aa7b645c5349963d2811b4ea3bc298d3655324`

## Manifests

| | Happ | Incy |
|---|---|---|
| Package | `su.happ.proxyutility` | `llc.itdev.incy` |
| versionName / Code | 4.9.0 / 1721 | 3.9.8 / — |
| minSdk / targetSdk | 24 / **37** | 24 / 36 |
| Permissions | 16 (incl. CAMERA — QR) | 15 |
| intent-filter schemes | `content`, `happ` | `incy`, `vless`, `vmess`, `ss`, `trojan`, `awg`, `amneziawg`, `vpn` |

Interesting: minSdk 24 for both (below our 26), and Happ is already on targetSdk 37.

## Cores

- **Happ: libxray** (`go/libxray/gojni`, `Libxray$proxyXRayVPNServiceSupportsSet`) —
  Xray-core, not mihomo.
- **Incy: Xray library** (`Error stopping Xray`, `Failed to parse xray JSON config`)
  — Xray as well.

Both are Xray clients: hence the "native" Xray JSON subscriptions in the ecosystem,
which we convert into mihomo YAML. Our conversion is the bridge between the ecosystems.

## Deep links (discovered)

- Happ: `happ://add/`, `happ://crypt` (no version), `crypt2/`, `crypt3/`, `crypt4/`, `crypt5/`.
  We support pass-through only for crypt4/crypt5 (the modern panel schemes);
  crypt2/3 produce a warning — legacy, left as-is.
- Incy: `incy://add/{url}`, `add/`, `autorouting/{,add,onadd}`, `backup/`, `close`,
  `connect`, `disconnect`, `crypt1/` — plus **direct node schemes** `vless://`,
  `vmess://`, `ss://`, `trojan://` as one-tap import. We cover add/import/
  crypt1/autorouting/connect/disconnect/toggle; `backup/` is not handled (warning).

## Headers and subscriptions

- Happ: `HWID` (dto), `Encrypt-Tag`, `User-Agent`/`User-agent`/`change-user-agent`,
  `manual-block-user-agent` (an option to disable the UA!), `fallback-url`.
- Incy: `profile-title:`, `profile-update-interval:`, `subscription-userinfo:`,
  `DeviceInfo(hwid=…)`, `RegisterArgs(hwid=…)` — **the hwid is registered on the panel**
  (not merely sent), and there is a "HWID collision repair" feature.
- Conclusion: our `x-hwid` + `Subscription-Userinfo` + `profile-title` +
  `profile-update-interval` match the ecosystem; the difference is that Incy registers
  the hwid via the panel API (RegisterArgs), while we send it as a header.

## Crypto

Both: `AES/GCM/NoPadding`, ChaCha20-Poly1305. Incy pulls in **Argon2** (a KDF for
crypt1-like schemes or local secrets). Our HappCrypto (AES-128-GCM,
fixed IV) covers `happ://crypt4/5` — the main panel use case.

## Network endpoints (from DEX strings)

- Happ: clean — library/docs domains only, no telemetry visible.
- Incy: `captive.apple.com` (portal check), DoH `1.1.1.1`/`cloudflare-dns.com`/`dns.google`,
  `https://check.incytv.com/pair/` (TV pairing), **`https://cloud.appwrite.io/v1`**
  (Appwrite cloud backend — accounts/analytics on their infrastructure),
  `https://aihelper.website/` (AI helper), geoip/geosite dat updates.

Mihomet has no external backends — in this comparison we are cleaner network-wise.

## Actionable takeaways for Mihomet

1. `happ://crypt2/3` and `incy://backup` — not supported (warning, nothing is lost).
   Implementing them would require reverse-engineering the legacy KDF — low priority.
2. AmneziaWG (`awg://`) in Incy — mihomo supports wireguard with amnezia parameters;
   if panels start handing out awg links, add a parser (backlog).
3. Direct node schemes (`vless://…` as one-tap) in Incy — we already cover this
   via URI-list import and share-target.
4. TV pairing (Incy) and the AI helper — features outside our scope.
5. Both clients are Xray-core. Our bet on mihomo + full Xray JSON conversion
   (the overnight work) covers the same class of subscriptions while preserving
   groups/routing.
