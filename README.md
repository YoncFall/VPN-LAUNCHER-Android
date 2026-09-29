# VPN LAUNCHER — Android edition

Android-версия VPN LAUNCHER (см. [PLAN-ANDROID.md](PLAN-ANDROID.md)).
Рядом: `../VPN-LAUNCHER-src` (v1.0.6, эталон), `../vpn-launcher-py` (desktop).

- Kotlin + Jetpack Compose, minSdk 24.
- Движок: sing-box через libbox.aar (GPL-3.0) — качается gradle-задачей,
  в git не коммитится.
- `:core` — порт desktop-ядра с golden-паритетом к 1.0.6 (фикстуры и
  эталоны копии из vpn-launcher-py/tests).

## Статус: этапы 1-5 (:core с golden-паритетом, Compose-раскладка собирается)

- `:core` — порт desktop-ядра: b64, URI-хелперы, TLS/транспорт,
  парсеры vless/vmess/trojan/ss/hysteria2/tuic, подписка (http/`file://`/путь),
  сборка конфига sing-box (build/write/new + test_sing_box_config).
- **Golden-паритет с 1.0.6 закреплён**: фикстуры и эталоны PS 5.1 скопированы
  из `vpn-launcher-py/tests` (снимок — `tools/make_golden.ps1` там же):
  `GoldenParseNodesTest` (parse-nodes*.json) и `GoldenConfigTest`
  (config-*.json + прогон через `sing-box check`, движок ищется как в
  conftest.py desktop-проекта).
- `gradlew :core:test` — **30 зелёных** (в т.ч. контроль кириллицы в строках,
  логические тесты конфига — порт tests/test_config.py).
- `:app` — MainActivity + Compose-раскладка в игровом стиле (порт window.py
  и виджетов theme.ps1: карточка с акцентными краями, кнопки/поля/радио/LED,
  секции ПОДПИСКА/СЕРВЕРЫ/РЕЖИМ/ИСКЛЮЧЕНИЯ, статус); `assembleDebug`
  собирает APK (скриншот-сверка с desktop — на устройстве, этап 8).
- Дальше: VpnService + libbox (этап 6), воркеры и логика (этап 7).

## Сборка

```powershell
# 1) local.properties уже настроен (sdk.dir)
# 2) тесты ядра (в JAVA_HOME — JDK 17, лежит в %LOCALAPPDATA%\jdk17):
.\gradlew :core:test
# 3) debug-APK (скачает libbox.aar ~118 МБ):
.\gradlew :app:fetchLibbox :app:assembleDebug
```
