# Mihomet

Современный и быстрый Android-клиент для ядра **Mihomo** (`Clash.Meta` v1.19+) с дизайном **Material 3 Expressive**.

---

## ⚡ Ключевые возможности

- **Два режима работы:**
  - **Non-Root (VPN):** стандартный Android `VpnService` со стеком TUN/gVisor (не требует root-прав).
  - **Root (TProxy):** прозрачное проксирование трафика через `iptables` / `nftables` с минимальной нагрузкой на батарею.
- **Интерфейс Material 3 Expressive:**
  - Тактильная плавающая навигация и интерактивные виджеты управления.
  - Настоящая глубокая **AMOLED-тема** (чистый OLED `#000000` и нейтральные графитовые контейнеры без лишних цветовых примесей).
  - Плавные кинематические переходы между экранами (Shared-Axis motion).
  - График трафика и мониторинг скорости в реальном времени.
- **Поддержка современных протоколов:**
  - VLESS (Reality, Vision), VMess, Shadowsocks (включая 2022), Trojan, Hysteria 2, TUIC.
- **Универсальные подписки:**
  - Автоматическая конвертация форматов (Base64, Sing-box JSON, Clash YAML).
  - Поддержка пользовательских заголовков при загрузке (`x-hwid` для Remnawave, кастомный `User-Agent` и формат JSON / `Ключ: Значение`).
  - Тестирование задержки узлов (HTTP, TCP, ICMP) даже при выключенном ядре.
- **Раздельное туннелирование (Per-App Proxy):**
  - Выбор приложений для обхода или направления в прокси (белый и чёрный списки).

---

## 📱 Системные требования

| Параметр | Значение |
|---|---|
| **Минимальная версия ОС** | Android 8.0 Oreo (API 26) |
| **Целевая версия ОС** | Android 15 / 16 (API 35+) |
| **Поддерживаемые архитектуры** | `arm64-v8a`, `armeabi-v7a`, `x86_64` |

---

## 🛠 Сборка проекта

### Требования
- JDK 21
- Android SDK (API 35, Build-Tools 35.0.0)

### Основные команды

```bash
# Проверка компиляции Kotlin
./gradlew compileDebugKotlin

# Запуск набора unit-тестов
./gradlew testDebugUnitTest

# Сборка Debug APK
./gradlew assembleDebug
# Результат: app/build/outputs/apk/debug/app-debug.apk

# Сборка Release APK
./gradlew assembleRelease
# Результат: app/build/outputs/apk/release/app-release.apk

# Установка на подключенное устройство через ADB
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 🏗 Архитектура и стек

- **Язык:** Kotlin 2.1 (Coroutines, Flow, Serialization)
- **UI:** Jetpack Compose (BOM 2024.12.01), Material 3 Expressive
- **DI:** Koin 3.5.6
- **Хранение данных:** Room (профили и сессии), DataStore Preferences (настройки)
- **Сетевой клиент:** Ktor 2.3.12 (загрузка подписок, WebSocket трафика, REST API ядра)
- **Ядро:** Precompiled native `libmihomo.so`
- **Документация для агентов:** [AGENT_GUIDE.md](file:///home/misha/Projects/mihomet/AGENT_GUIDE.md)
