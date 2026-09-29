# VPN LAUNCHER — план Android-версии

Дата: 2026-09-29. Статус: **этапы 1-5 готовы** (коммиты `0af375e`, `1fe8370`
+ коммит этапа 5): тулчейн установлен, скаффолд собирается, `:core` портирован
и держит golden-паритет (`gradlew :core:test` — 30 зелёных: парсеры подписки,
сборка конфига, сверка с эталонами PS 5.1 `parse-nodes*.json`/`config-*.json` +
прогон через `sing-box check`), `:app` собирает debug-APK с Compose-раскладкой
в игровом стиле. Рядом лежат `../VPN-LAUNCHER-src`
(v1.0.6, эталон поведения) и `../vpn-launcher-py` (desktop-переписка,
этапы 1-2 готовы).

## Цель

Тот же продукт на Android: подписка → список нод → конфиг sing-box →
TUN-туннель, с сохранением **golden-паритета с 1.0.6** (общие фикстуры
и эталоны вывода с desktop-проекта).

## Ключевые решения (исследовано 29.09.2026)

1. **Стек: Kotlin + Jetpack Compose**, minSdk 24, target/compileSdk 36,
   Java 17, Gradle 8.13 + AGP 8.11.1 + Kotlin 2.1.20, Compose BOM 2026.06.01.
   - Android VPN требует `VpnService` (нативный Java API) — Python/Kivy
     не подходят; Compose рисует наш игровой стиль вручную (аналог QSS).
   - Не форк SFA: UI SFA — чужой дизайн; нам нужна своя 1.0.6-раскладка
     и свой core с golden-тестами. SFA (github.com/SagerNet/
     sing-box-for-android, GPL-3.0) взят как референс архитектуры.
2. **Движок: libbox.aar** — официальные gomobile-биндинги sing-box
   (пакет `io.nekohasekai.libbox`, GPL-3.0):
   - Адрес AAR (версия **1.14.1** = ветка desktop-движка 1.14.x):
     `https://github.com/singbox-android/libbox/releases/download/1.14.1/libbox.aar`
     (118 МБ, sha256 `93b2596c4e90df32463a9ade5c89d85f83a16aacd94928ba26fc4bce41eaf2a9`);
     зеркало JitPack: `https://jitpack.io/com/github/singbox-android/libbox/<ver>/libbox-<ver>.aar`;
     self-build из исходников sing-box: `make lib_install && make lib_android`.
   - AAR в git не коммитится — качается gradle-задачей `:app:fetchLibbox`
     в `app/libs/libbox.aar` (паттерн rong001/usgate-client).
   - API (проверен по usgate-client, Apache-2.0 — переносим идеи):
     * `Libbox.version()`, `Libbox.checkConfig(json)` (аналог `sing-box check`);
     * `CommandServer(handler, platformInterface)` → `start()` →
       `startOrReloadService(config, OverrideOptions())`;
       `closeService()` / `close()`;
     * `PlatformInterface`: `openTun(TunOptions) -> fd` (строим TUN через
       `VpnService.Builder.establish()`), `autoDetectInterfaceControl(fd)`
       (→ `service.protect(fd)`), мониторинг сети, `getInterfaces`,
       `findConnectionOwner` (API 29+), `systemCertificates`, …;
     * `CommandServerHandler`: `serviceStop/serviceReload/
       getSystemProxyStatus/setSystemProxyEnabled/writeDebugMessage`.
3. **Модули**:
   - `:core` — чистый Kotlin/JVM (без Android): порт `vpn_launcher/core`
     (base64, query-хелперы, парсеры vless/vmess/trojan/ss/hysteria2/tuic,
     подписка, сборка конфига) + **golden-тесты на тех же файлах**, что и
     desktop (`tests/fixtures`, `tests/golden` — копия из vpn-launcher-py).
   - `:app` — Compose UI (палитра/раскладка из `vpn_launcher/ui/theme.py`
     и `window.py`) + `VpnService` + libbox-мост.
4. **Паритет**: сверка с 1.0.6 идёт против тех же golden JSON
   (`parse-nodes`, `parse-nodes-b64`, `config-tun`, `config-proxy-selected`,
   `config-single`); `Libbox.checkConfig` дополнительно гоняет конфиги.
   Платформенные отличия (документируются в README):
   - Android-specific секции TUN (auto_route/exclude-package, MTU, уведомление);
   - нет системного прокси и списка исключений процессов Windows;
   - ping нод — свой TCP-пинг из :core (как на desktop).
5. **Лицензия**: GPL-3.0 (как sing-box и desktop-версия); APK-релиз =
   публикация исходников по GPL.

## Этапы

| # | Этап | Результат |
|---|------|-----------|
| 1 | Тулчейн: JDK 17, Android SDK (platform 36, build-tools 36), Gradle wrapper 8.13 | **готово** |
| 2 | Скаффолд: settings/root build/modules/local.properties/git | **готово** (`0af375e`) |
| 3 | `:core`: порт парсеров URI/протоколов + golden `parse-nodes*.json` | **готово** |
| 4 | `:core`: сборка конфига + golden `config-*.json` + `sing-box check` | **готово**: 30 тестов зелёные |
| 5 | `:app`: MainActivity + Compose-раскладка в игровом стиле (поле, кнопки, секции, радио, статус) | **готово**: APK собирается; скриншот-сверка — на устройстве (этап 8) |
| 6 | VpnService + libbox-мост: fetchLibbox, PlatformInterface, CommandServer, уведомление | VPN поднимается на устройстве |
| 7 | Воркеры: подписка (HTTP), TCP-пинг, выбор ноды, режимы TUN/proxy | UX как desktop |
| 8 | Сборка release APK, установка, E2E-прогон | готовый продукт |

## Окружение (сегодня)

- JDK: Temurin 17.0.20.1, zip в `%LOCALAPPDATA%\jdk17` (winget споткнулся
  о хэш манифеста — качали напрямую с api.adoptium.net).
- Android SDK: `%LOCALAPPDATA%\Android\Sdk` — cmdline-tools, platform-tools,
  `platforms;android-36`, `build-tools;36.0.0` (лицензии приняты пайпом `y`).
- `local.properties` (sdk.dir, unicode-escape кириллицы) — в .gitignore.

## Нюансы Windows-окружения (проверено вживую)

- **`@argfile` test-worker'а JDK читает в ANSI (CP_ACP), а не в UTF-8** —
  при `file.encoding=UTF-8` у daemon Gradle воркер не стартует с кириллицей
  в путях (`ClassNotFoundException: GradleWorkerMain`). Решение:
  `org.gradle.jvmargs=-Dfile.encoding=Cp1251`. Побочно: JDK в `@argfile`
  трактует `\` как escape **внутри кавычек** (пути без пробелов Gradle
  пишет без кавычек — работает). Проверено отдельными экспериментами
  (java @argfile: ASCII ok / UTF-8 fail / CP1251 ok).
- `android.overridePathCheck=true` — AGP запрещает кириллицу в пути
  проекта (b.android.com/95744), штатный обход из сообщения об ошибке.

## Риски / открытые вопросы

- Размер APK: +~118 МБ libbox.aar (ABI-split: arm64-v8a+armeabi-v7a;
  SFA использует splits по ABI). Решение: universal + splits, как SFA.
- E2E-тесты только на устройстве/эмуляторе (на машине устройство
  подключается вручную; эмулятор — опционально, HAXM/KVM на Windows).
- Пиннинг версий «как у SFA» (compileSdk 37) — откладываем до стабильности;
  36 достаточно.
