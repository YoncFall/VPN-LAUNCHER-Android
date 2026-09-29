# VPN LAUNCHER — Android edition

Android-версия VPN LAUNCHER (см. [PLAN-ANDROID.md](PLAN-ANDROID.md)).
Рядом: `../VPN-LAUNCHER-src` (v1.0.6, эталон), `../vpn-launcher-py` (desktop).

- Kotlin + Jetpack Compose, minSdk 24.
- Движок: sing-box через libbox.aar (GPL-3.0) — качается gradle-задачей,
  в git не коммитится.
- `:core` — порт desktop-ядра с golden-паритетом к 1.0.6 (фикстуры и
  эталоны копии из vpn-launcher-py/tests).

## Статус: этапы 1-7 (:core с golden-паритетом, логика подключения собрана)

- `:core` — порт desktop-ядра: b64, URI-хелперы, TLS/транспорт,
  парсеры vless/vmess/trojan/ss/hysteria2/tuic, подписка (http/`file://`/путь),
  сборка конфига sing-box (build/write/new + test_sing_box_config),
  TCP-пинг (Latency.kt) и состояние (State.kt, state.json).
- **Golden-паритет с 1.0.6 закреплён**: фикстуры и эталоны PS 5.1 скопированы
  из `vpn-launcher-py/tests` (снимок — `tools/make_golden.ps1` там же):
  `GoldenParseNodesTest` (parse-nodes*.json) и `GoldenConfigTest`
  (config-*.json + прогон через `sing-box check`, движок ищется как в
  conftest.py desktop-проекта).
- `gradlew :core:test` — **35 зелёных** (в т.ч. контроль кириллицы в строках,
  логические тесты конфига — порт tests/test_config.py; пинг/состояние —
  порт tests/test_latency.py и tests/test_state.py).
- `:app` — MainActivity + Compose-раскладка в игровом стиле (порт window.py
  и виджетов theme.ps1: карточка с акцентными краями, кнопки/поля/радио/LED,
  секции ПОДПИСКА/СЕРВЕРЫ/РЕЖИМ/ИСКЛЮЧЕНИЯ, статус); `assembleDebug`
  собирает APK (скриншот-сверка с desktop — на устройстве, этап 8).
- **VpnService-мост libbox (этап 6)**: `VpnServiceImpl` (CommandServer
  lifecycle + Libbox.setup + foreground-нотификация systemExempted по
  образцу SFA), `PlatformBridge` (openTun: адреса/маршруты/per-app из
  TunOptions, protect(fd), getInterfaces, findConnectionOwner API 29+),
  `NetworkMonitor` (монитор дефолтной сети), уведомления движка;
  отклонения от SFA задокументированы в докстрингах (нет shell/root/bridge,
  exclude-маршруты режет правило LOCAL_CIDRS конфига).
- **Воркеры и логика подключения (этап 7)**: `AppViewModel` — порт
  обработчиков VPN.ps1 (загрузка подписки, TCP-пинг с прогресс-статусом,
  выбор ноды, исключения с валидацией `.exe`, ПОДКЛЮЧИТЬСЯ/ОТКЛЮЧИТЬ/
  Проверить конфиг, egress-IP по таймеру); состоянием делятся
  `VpnRuntime` (сервис) и UI; согласие `VpnService.prepare` вместо UAC;
  проверка конфига — `Libbox.checkConfig(json)`; журнал в памяти
  (экран вместо блокнота). Отклонения от 1.0.6 — в докстринге AppViewModel.
- Дальше: устройство/E2E — подъём VPN, скриншот-сверка, иконка (этап 8).

## Сборка

```powershell
# 1) local.properties уже настроен (sdk.dir)
# 2) тесты ядра (в JAVA_HOME — JDK 17, лежит в %LOCALAPPDATA%\jdk17):
.\gradlew :core:test
# 3) debug-APK (скачает libbox.aar ~118 МБ):
.\gradlew :app:fetchLibbox :app:assembleDebug
```
