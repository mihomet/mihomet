# Совместимость с экосистемой Happ / Incy / CMFA / FlClash

> Результат research (источники: FlClash `chen08209/FlClash`, CMFA `MetaCubeX/ClashMetaForAndroid`,
> Happ dev-docs `HappDev/happ_su`, Incy docs `INCY-DEV/incy-docs`, панельные реализации
> `MHSanaei/3x-ui internal/sub/happ.go|incy.go`, SRR-пресеты Remnawave).

## Deep-link схемы (поддержка в mihomet: `core/DeepLinkParser.kt`)

| Схема | Формат | Статус |
|---|---|---|
| `clash://install-config?url=&name=&type=url&update-interval=` | CMFA/FlClash, host строго install-config | ✅ |
| `clashmeta://…`, `flclash://…` | аналогично clash | ✅ |
| `v2rayng://install-sub?url=` | v2rayNG | ✅ |
| `sing-box://import-remote?url=` | SFA | ✅ |
| `happ://add/<base64-payload>` | однотап добавление узла | ✅ |
| `happ://routing/add|onadd/{base64-JSON}` | роутинг-профиль (GlobalProxy, DirectSites…) | ✅ (onadd = импорт + активировать) |
| `happ://crypt4/`, `crypt5/` | зашифрованная подписка (AES-128-GCM, `crypto.happ.su`) | ✅ расшифровка по спеке (ключница в DataStore, IV 'kkkkkkkkkkkk', тег в encrypt-tag); keyring-UI — TODO |
| `incy://add/<url>`, `incy://import/{data}` | Incy | ✅ |
| `incy://crypt1/<payload>` | base64url(iv‖ct‖tag) | passthrough (warning) |
| `incy://connect|disconnect|toggle`, `mihomet://start|stop` | управление VPN по ссылке | ✅ (MainActivity.handleControlLink) |

## Заголовки, которые шлют/читают Happ и Incy

- **UA**: Happ шлёт `Happ/<ver>`, Incy — `INCY/<ver>/<platform>`. SRR-панели (Remnawave
  Subscription Response Rules) матчат UA и отдают `XRAY_JSON` / `MIHOMO` / `SINGBOX` /
  `XRAY_BASE64` / HTML. mihomet шлёт честный `Mihomet/<ver>` первым (попадает в MIHOMO-тир),
  далее UA-фолбэки Happ/sing-box/ClashMeta/v2rayNG.
- **`x-hwid`** (alnum 10–64) — device-binding при лимитах подключений (SHA-256 от ANDROID_ID).
- Happ-заголовки: `profile-title`, `profile-update-interval`, `subscription-userinfo`,
  `New-Url` / `Fallback-Url` (авто-переезд подписки), `ProviderID`, `Sub-Info-Color`,
  `Subscription-Always-Hwid-Enable`, `Tun-Mode`, `Per-App-Proxy-Mode/List`, `encrypt-tag`.
- Incy добавляет: `support-url`, `announce-url`, `banner-*`, `sort-order` (`base64:<…>`),
  `fragmentation-*`, `noises-*`.
- Шифрование содержимого подписки: AES-128-GCM, IV `"kkkkkkkkkkkk"`, тег `encrypt-tag`
  (mihomet пока не поддерживает — passthrough с warning).

## Borrowed patterns (FlClash / CMFA) — что уже в mihomet

- own-uid exclusion в VPN builder (`addDisallowedApplication(packageName)`) ✅
- Кнопка Stop в уведомлении ✅
- `setUnderlyingNetworks` + NetworkCallback при смене сети ✅

## Дорожная карта (из TOP-8 research)

1. ✅ Deep-link импорт (см. таблицу)
2. ✅ Watchdog ядра (авторестарт с backoff, 3 попытки)
   Attribution: non-root TUN core patch taken from Tropa (nvsbunny/tropa,
   tools/mihomo-android-nonroot.patch @ v0.2.5) — репозиторий mihomet/core,
   GPL-3.0 совместим. VERIFIED on API 35 emulator (non-root): TUN adapter up
   (no packages.xml error), real app traffic routed via the selected proxy node
   (mihomo.log: [TCP] … match Match using → Remnawave[🇫🇷]).
   API-LEVEL MATRIX (emulator): API 26 minSdk ✅ (TUN up + routed traffic + deep-link
   import), API 29 ✅ (TUN up), API 35 ✅. Found+fixed by the matrix: the first patched
   build targeted NDK API 34 and silently exited on Android 8-13 — rebuilt targeting
   android-26 (core-v1.19.33-mihomet.2). Audit tails closed:
   readiness-gate before 'connected', watchdog stability reset, mode-switch
   serialization + root-firewall cleanup on VPN start, updater rollback guard,
   empty-whitelist semantics + own-package loop guard, YAML highlighting.
3. ✅ Honest UA `Mihomet/<ver>` + `x-hwid` + profile-title / New-Url
4. ✅ Builder: `setBlocking(false)`, `setMetered(false)`, `setConfigureIntent`
5. ✅ `FOREGROUND_SERVICE_IMMEDIATE` + core watchdog (auto-restart ×3) + Always-on (null-intent restart, onRevoke) + root-daemon watchdog (10s monitor, backoff ×3) + автообновление подписок по update-interval (WorkManager)
6. ⬜ Смена сети → реконнект core-соединений (аналог FlClash `Core.resetNetwork`), screen-on обновление скорости
7. ⬜ Always-on VPN + `onRevoke` graceful
7.5 ✅ Реализовано дополнительно (утренняя сессия + волны по повторному аудиту):
   - Исправлено: whitelist-инверсия iptables, IPv6 mark 20/25 по family, fail-closed
     на невалидном профиле + last-known-good, mixed-port убран, Keystore lifecycle,
     readiness-гейт (/configs tun.enable), OEM chain save/restore, updater rollback.
   - В работе (волна A): firewall DNS-hijack для TCP:53 + unified empty-whitelist,
     VPN exit-ownership/cleanup, root readiness + rollback, watchdog stability window.
   - Волна A (сделано): firewall DNS-hijack для TCP:53 + unified empty-whitelist,
     VPN exit-ownership/cleanup, root readiness + rollback, watchdog stability window.
   - Волна H (в работе): сервисы (отсутствующий файл выбранного профиля = fail-closed,
     семантическая валидация до запуска, per-app фактически добавленные пакеты,
     сериализация root start/stop, VPN last-good регенерация), OEM-снапшот атомарный
     (tmp+mv, flush гейтом), Keystore gate консервативный (enc1: в DataStore/Room/keyring),
     encrypt бросает вместо тихого plaintext.
   - Остаток (P2): DoH UI-дисклеймер, updater scope disclosure готов (частично),
     subconverter, JS-override — в TODO.
 виджет рабочего стола (status+toggle),
    автостарт на загрузке (последний успешный режим), TUN stack/MTU/bypass-LAN (CMFA
    additive-паттерн), QR-код подписки, стриминг-лимит тела 20 МБ, экспорт/шеринг логов,
    memory/connections на дашборде. N3 в работе: battery-промпты, allowBypass, HTTP-proxy
    через VPN.
8. ◐ Override-слой поверх профиля: базовая typed-версия готова (unified-delay / tcp-concurrent / log-level / sniffer в Настройки→Продвинутые, приоритет над профилем); per-profile override-патч готов (Room v4 `overridePatch`, YAML-фрагмент в меню профиля, глубокий merge в runtime-конфиг ДО обязательных настроек — allow-lan/порты/DNS приложения всегда приоритетны). Остаток (L): полный блок dns (nameserver-policy, fake-ip-filter-mode), sniffer per-protocol, JS-скрипты как в FlClash.
