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
  и виджетов theme.ps1: карточка с акцентными краями, кнопки/поля/LED,
  секции ПОДПИСКА/СЕРВЕРЫ/ИСКЛЮЧЕНИЯ, статус); `assembleDebug`
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
  выбор ноды, исключения (пакеты приложений, `addDisallowedApplication`),
  ПОДКЛЮЧИТЬСЯ/ОТКЛЮЧИТЬ/
  Проверить конфиг, egress-IP по таймеру); состоянием делятся
  `VpnRuntime` (сервис) и UI; согласие `VpnService.prepare` вместо UAC;
  проверка конфига — `Libbox.checkConfig(json)`; журнал в памяти
  (экран вместо блокнота). Отклонения от 1.0.6 — в докстринге AppViewModel.
- **Иконка (этап 8, обновлена в 2.0.1)**: mipmap из нового artwork `icon-1024-preview.png` (1024×1024, вне репо — `Desktop\Значки`), до 2.0.1 — из `app.ico` 1.0.6:
  legacy PNG (mdpi…xxxhdpi) + adaptive-icon для API 26+ (фон `#13161D` ≈
  тёмное поле нового artwork, foreground = контент 2/3 холста, чтобы
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
     через `\p{L}\p{N} + UNICODE_CASE` (`ExeNameTest`, 7 JVM-тестов;
     впоследствии заменено на `PACKAGE_RE` — см. ниже).
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
  6. ~~Строка режима «Весь трафик - TUN»~~ — **секция РЕЖИМ удалена** после
     v2.0.0 (см. «Доработки после первого релиза», п. 4).
- **Доработки после первого релиза (по отзывам с устройства)**:
  1. **ИСКЛЮЧЕНИЯ стали по-настоящему android-ными**: вместо .exe-имён
     процессов — пакеты установленных приложений: кнопка «Приложения»
     открывает picker (label + пакет из `PackageManager`), можно вписать
     пакет вручную; эффект = `VpnService.addDisallowedApplication`
     (список хранится в prefs сервиса, применяется в
     `PlatformBridge.openTun`, попадает в журнал: `excluded apps: ...`).
     `EXE_RE` заменён на `PACKAGE_RE` (`PackageNameTest`, 8 JVM-тестов).
  2. Кнопка режима «Системный прокси» удалена из секции РЕЖИМ — на
     Android системного прокси нет, остался только TUN (секция РЕЖИМ
     удалена целиком — см. п. 4).
  3. **Диагностика «через TUN ничего не грузит»** (проверено на устройстве
     и ПК): подписка работает (egress-IP через туннель, HTTP 200 через узел
     с ПК), авто-тест выбирает живую ноду; ловушка — ручной выбор мёртвого
     узла подписки («Трафик закончился», «Продлите в боте»): туннель
     поднимается, трафик не идёт, egress = «Внешний IP недоступен».
     Лечение — снять выбор (авто-тест всех) или взять живую ноду.
  4. **Секция РЕЖИМ удалена**: радио «Весь трафик - TUN» было единственным
     (выбора не было), вся строка режима бесполезна. Вместе с ней удалены
     `GameRadio`, `selectMode()` и поле `mode` из `UiState` (в
     state.json/config.json поле mode остаётся — это ядро, `StateTest`).
     Удаление держит тест `ModeSectionRemovedTest`.
  5. **Кнопка обновления подписки «⟳ Обновить»** в строке ПОДПИСКА —
     как обновление в браузере: перечитывает URL, обновляет список
     серверов и сразу перепинговывает (`AppViewModel.refreshSubscription`
     = `loadImpl(afterSuccess = pingAll)`; автопинг вызывается после
     finally-сброса, чтобы очистка не затёрла новые пинги). «Загрузить
     подписку» и «⟳ Обновить» — цветом как «ПОДКЛЮЧИТЬСЯ» (`ACCENT`),
     чтобы пользователь их видел.
  6. **«Открыть лог» перенесено вниз** — под строку LED/статуса (сверху
     освободили место под «⟳ Обновить»); **кнопка «Добавить» в
     ИСКЛЮЧЕНИЯХ убрана** — пакет вводится в поле и подтверждается
     клавишей «Готово» (onDone = `addExclusion`), picker «Приложения»
     добавляет сам.
   7. **Безопасность** (по запросу владельца):
      - **запрет HTTP на всех версиях Android**: `network_security_config`
        с `cleartextTrafficPermitted="false"` (в манифесте
        `networkSecurityConfig`). minSdk 24 — на Android 7/7.1 cleartext
        разрешён по умолчанию, `http://`-ссылка на подписку уходила бы
        открытым текстом; теперь везде CLEARTEXT-ошибка в журнал вместо
        утечки токена. Стек sing-box (Go) от конфига не зависит.
      - **маска токена подписки в поле ввода**: `maskSubscriptionUrl` (core)
        оставляет `scheme://host/…`, путь/токен скрыт; `GameField(mask=…)`
        показывает маску вне фокуса, тап раскрывает поле с клавиатурой,
        потеря фокуса снова маскирует — случайный скриншот/запись экрана
        не покажет токен. **`FLAG_SECURE` сознательно НЕ ставим** —
        скриншоты разрешены (решение владельца, на совести пользователя).
      - **kill switch**: раньше авария движка (`serviceStop`) гасила
        сервис — туннель закрывался и весь трафик уходил напрямую без
        VPN. Теперь `VpnServiceImpl.engineStopRequested`/`failStart` при
        живом `tunDescriptor` сервис не останавливают: TUN остаётся
        открытым и гасит трафик, уведомление «Соединение оборвалось —
        трафик заблокирован», UI показывает красный статус и включает
        только кнопку «ОТКЛЮЧИТЬ» (`VpnRuntime.killSwitch`, сбрасывается в
        `closeEngine`). Отклонение от SFA: процесс, убитый системой, снимает
        маршруты сам — это предел Android (та же особенность у SFA).
      Тесты: `SubscriptionMaskTest` (ядро, 7) + `SecurityHardenTest` (app, 4).
- **Опубликовано**: репо https://github.com/YoncFall/VPN-LAUNCHER-Android
  (GPL-3.0), релизы **v2.0.0** (первый) и **v2.0.1** (безопасность + правки
  UI по отзывам) с APK под стабильными именами
  (`releases/latest/download/VPN-LAUNCHER.apk` + arm64-v8a/armeabi-v7a),
  карточка Android на сайте https://yoncfall.github.io/ (скриншот — без
  URL подписки).

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
