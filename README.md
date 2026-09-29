# VPN LAUNCHER — Android edition

Android-версия VPN LAUNCHER (см. [PLAN-ANDROID.md](PLAN-ANDROID.md)).
Рядом: `../VPN-LAUNCHER-src` (v1.0.6, эталон), `../vpn-launcher-py` (desktop).

- Kotlin + Jetpack Compose, minSdk 24.
- Движок: sing-box через libbox.aar (GPL-3.0) — качается gradle-задачей,
  в git не коммитится.
- `:core` — порт desktop-ядра с golden-паритетом к 1.0.6 (фикстуры и
  эталоны копии из vpn-launcher-py/tests).

## Статус: этапы 1-8 — E2E на реальном устройстве пройден

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
- **Иконка (этап 8)**: ресурсы mipmap сгенерированы из `app.ico` 1.0.6 —
  legacy PNG (mdpi…xxxhdpi) + adaptive-icon для API 26+ (фон `#13161D`
  сэмплом тёмного поля иконки, foreground = контент 2/3 холста, чтобы
  рамка попадала под все маски лаунчеров); `android:icon` в манифесте.
- **Release-сборка и подпись (этап 8)**: подпись RSA-2048 читается из
  `key.properties` в корне репо (в git не попадает — .gitignore), keystore
  лежит вне репо: `Documents\VPN-LAUNCHER-keystore\` (внутри README.txt
  с паролем и требованием резервной копии; без кепки нельзя издавать
  обновления установленных версий). Сплиты `universal + arm64-v8a +
  armeabi-v7a` («universal + splits, как SFA» из плана): 59,6 / 33,5 /
  33,4 МБ, `apksigner verify` — 0. Версия продукта **2.0.0** — общая
  с desktop.
- **E2E на реальном устройстве (этап 8)**: TECNO SPARK Go 2 (Android 15,
  720x1600), установка по Wi-Fi adb, release-APK 2.0.0. Полный цикл
  дважды: холодный старт → автозагрузка подписки («Серверов загружено:
  13» без тапов) → пинг (34–71 мс) → системное согласие VPN (диалог
  «Отменить/ОК» проверен принудительным `appops ACTIVATE_VPN deny`) →
  подключение (tun0 `172.19.0.1/30`, «ПОДКЛЮЧЕНО», внешний IP ноды) →
  отключение (tun0 гаснет, сервис уничтожается, нотификация снимается,
  журнал пишет остановку по шагам 1/4…4/4) → переподключение →
  повторное отключение. «Проверить конфиг», журнал — тоже в E2E.
- **Починено по итогам E2E** (все правки покрыты тестами/прогонами):
  1. `EXE_RE` в `AppViewModel`: `UNICODE_CHARACTER_CLASS` на Android
     отсутствует и ронял `<clinit>` при старте — Unicode-имена теперь
     через `\p{L}\p{N} + UNICODE_CASE` (`ExeNameTest`, 7 JVM-тестов).
  2. **Остановка туннеля**: система удерживает сервис связью
     `android.net.VpnService`, пока жив tun, — `stopService`/`stopSelf`
     не доходили до `onDestroy` (взаимоблокировка: «Отключено» при
     живом tun0). Фикс по паттерну SFA: остановка **сначала закрывает
     tun-дескриптор** (`pfd.close()` вместо `detachFd()`, шаг 1/4), затем
     `closeService`/`close` и уже потом сервис уничтожается; `stop()`
     идёт прямым вызовом из того же процесса. Отклонение от 1.0.6
     задокументировано в докстринге `VpnServiceImpl.stop`.
  3. Автозагрузка подписки на холодном стартe (URL сохраняется; тихий
     режим без диалога) — после убийства процесса HiOS список больше
     не пустой.
  4. Тап «Подключиться» во время идущей загрузки — статус вместо
     диалога ошибки.
  5. Автоподбор шрифта кнопок и капс-заголовков (`GameButton`,
     `CapsLabel`, шапка «ПРОТОКОЛ») — на 720p подписи не обрезаются.
  6. Строка режима на Android — «Весь трафик - TUN» (короче desktop-ной
     «Весь трафик - TUN (нужен админ)»: TUN на Android не требует
     прав администратора; комментарий в `AppScreen.kt`).
- Дальше: публикация android-репо и карточки на сайте (E2E пройден).

## Сборка

```powershell
# 1) local.properties уже настроен (sdk.dir)
# 2) тесты ядра (в JAVA_HOME — JDK 17, лежит в %LOCALAPPDATA%\jdk17):
.\gradlew :core:test
# 3) debug-APK (скачает libbox.aar ~118 МБ):
.\gradlew :app:fetchLibbox :app:assembleDebug
# 3б) debug-под эмулятор (добавляет x86_64; E2E без устройства):
.\gradlew :app:assembleDebug -Pemu
# 4) release-APK (key.properties с паролем кепки — в корне, gitignored;
#    без него собирается app-release-unsigned.apk):
.\gradlew :app:assembleRelease
#    -> app\build\outputs\apk\release\{app-universal,app-arm64-v8a,
#       app-armeabi-v7a}-release.apk
```
