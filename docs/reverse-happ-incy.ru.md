# Reverse notes: Happ 4.9.0 и Incy 3.9.8 (02–03.11.2026)

Статический разбор официальных релизных APK (публичные GitHub-релизы). Цель —
сравнить реализацию экосистемных клиентов с Mihomet и поймать пробелы совместимости.
Инструмент: androguard (манифест + строки DEX), без декомпиляции в Java.

- Happ: `Happ-proxy/happ-android` release 4.9.0, `Happ.apk`, sha256
  `47aac2a2310553ca85ef390c2ae1674d79d9d70c215ef36a35cdf433f5b8323d`
- Incy: `INCY-DEV/incy-platforms` release android-v3.9.8, `Incy.apk`, sha256
  `235d433346fb2c6371133d9952aa7b645c5349963d2811b4ea3bc298d3655324`

## Манифесты

| | Happ | Incy |
|---|---|---|
| Пакет | `su.happ.proxyutility` | `llc.itdev.incy` |
| versionName / Code | 4.9.0 / 1721 | 3.9.8 / — |
| minSdk / targetSdk | 24 / **37** | 24 / 36 |
| Разрешений | 16 (вкл. CAMERA — QR) | 15 |
| Схемы intent-filter | `content`, `happ` | `incy`, `vless`, `vmess`, `ss`, `trojan`, `awg`, `amneziawg`, `vpn` |

Интересно: minSdk 24 у обоих (ниже нашего 26) и Happ уже на targetSdk 37.

## Ядра

- **Happ: libxray** (`go/libxray/gojni`, `Libxray$proxyXRayVPNServiceSupportsSet`) —
  Xray-core, не mihomo.
- **Incy: Xray library** (`Error stopping Xray`, `Failed to parse xray JSON config`)
  — тоже Xray.

Оба — Xray-клиенты: отсюда и «родные» Xray JSON подписки в экосистеме, которые
мы конвертируем в mihomo YAML. Наша конвертация — мост между экосистемами.

## Deep links (обнаруженные)

- Happ: `happ://add/`, `happ://crypt` (без версии), `crypt2/`, `crypt3/`, `crypt4/`, `crypt5/`.
  Мы поддерживаем pass-through только crypt4/crypt5 (современные схемы панелей);
  crypt2/3 уходят в warning — легаси, оставляем как есть.
- Incy: `incy://add/{url}`, `add/`, `autorouting/{,add,onadd}`, `backup/`, `close`,
  `connect`, `disconnect`, `crypt1/` — плюс **прямые схемы узлов** `vless://`,
  `vmess://`, `ss://`, `trojan://` как one-tap импорт. Мы покрываем add/import/
  crypt1/autorouting/connect/disconnect/toggle; `backup/` не обрабатываем (warning).

## Заголовки и подписки

- Happ: `HWID` (dto), `Encrypt-Tag`, `User-Agent`/`User-agent`/`change-user-agent`,
  `manual-block-user-agent` (опция отключить UA!), `fallback-url`.
- Incy: `profile-title:`, `profile-update-interval:`, `subscription-userinfo:`,
  `DeviceInfo(hwid=…)`, `RegisterArgs(hwid=…)` — **hwid регистрируется на панели**
  (не просто шлётся), есть «HWID collision repair».
- Вывод: наш `x-hwid` + `Subscription-Userinfo` + `profile-title` +
  `profile-update-interval` соответствуют экосистеме; различие — Incy регистрирует
  hwid через API панели (RegisterArgs), мы шлём заголовком.

## Крипто

Оба: `AES/GCM/NoPadding`, ChaCha20-Poly1305. Incy тянет **Argon2** (KDF для
crypt1-подобных схем или локальных секретов). Наша HappCrypto (AES-128-GCM,
фиксированный IV) покрывает `happ://crypt4/5` — основной кейс панелей.

## Сетевые endpoints (из строк DEX)

- Happ: чисто — домены библиотек/доков, телеметрии не видно.
- Incy: `captive.apple.com` (проверка портала), DoH `1.1.1.1`/`cloudflare-dns.com`/`dns.google`,
  `https://check.incytv.com/pair/` (TV-pairing), **`https://cloud.appwrite.io/v1`**
  (облачный бэкенд Appwrite — аккаунты/аналитика их инфраструктуры),
  `https://aihelper.website/` (AI-хелпер), geoip/geosite dat обновления.

Mihomet внешних бэкендов не имеет — в этом сравнении мы чище по сети.

## Совместимые выводы для Mihomet

1. `happ://crypt2/3` и `incy://backup` — не поддерживаем (warning, не теряем).
   Позвать реализовать можно только реверсом легаси-KDF — низкий приоритет.
2. AmneziaWG (`awg://`) у Incy — mihomo умеет wireguard с amnezia-параметрами;
   если панели начнут раздавать awg-ссылки, добавить парсер (backlog).
3. Прямые схемы узлов (`vless://…` как one-tap) у Incy — у нас это и так работает
   через импорт URI-списков и share-target.
4. TV-pairing (Incy) и AI-хелпер — фичи вне нашего скоупа.
5. Оба клиента — Xray-core. Наша ставка на mihomo + полную конвертацию Xray JSON
   (ночная работа) закрывает тот же класс подписок с сохранением групп/роутинга.
