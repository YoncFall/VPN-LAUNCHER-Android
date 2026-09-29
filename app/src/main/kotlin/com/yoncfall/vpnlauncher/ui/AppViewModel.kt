// Логика главного экрана: воркеры подписки/пинга и кнопки подключения.
// Порт обработчиков VPN.ps1 (btnLoad 120-156, btnPing 379-436 + PingTick
// 335-377, btnConnect 458-575, btnDisconnect 577-589, btnTestCfg 591-611,
// исключения 252-306, Tick 615-641) с coroutines вместо runspace 1.0.6.
//
// Отклонения от 1.0.6 (документированы, поведение VPN не меняется):
//   - MessageBox -> GameDialog в стейте (тот же текст и заголовки);
//   - «Открыть лог» -> встроенный экран журнала: журнала-файла нет, AppLog
//     живёт в памяти (буфер 500 строк), формат строки - как write_log
//     'yyyy-MM-dd HH:mm:ss  <msg>';
//   - системный прокси недоступен на Android -> сегмент режима не
//     переключается, показывается статус (самоотключение: режим всегда TUN);
//   - elevation (UAC) не нужно -> вместо него системное согласие
//     VpnService.prepare, запрашивается после проверки конфига (в 1.0.6 UAC
//     - до генерации); отмена = 'Отменено - подключение не выполнено';
//   - проверка конфига: Libbox.checkConfig(json) вместо процесса
//     `sing-box check` (движок - встроенный libbox, не отдельный процесс);
//   - статус 'ПОДКЛЮЧЕНО' без части 'pid N' (движок in-process);
//   - список процессов Windows не показывается (на Android нет): только
//     ручной ввод .exe, правила process_name в конфиге остаются как в 1.0.6
//     (по Android-процессам они ни на что не влияют);
//   - одиночное выделение: тап по выбранной строке снимает её (WinForms не
//     даёт снять выделение кликом - «пусто» достижимо только до первого
//     выбора);
//   - серый PS для 'Внешний IP недоступен' -> TextDim (косметика);
//   - цвет статуса 'Генерация конфига...'/'Запуск sing-box...' не меняется
//     (как в PS - ForeColor не переназначался).
package com.yoncfall.vpnlauncher.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yoncfall.vpnlauncher.core.AppLog
import com.yoncfall.vpnlauncher.core.ConfigError
import com.yoncfall.vpnlauncher.core.VpnState
import com.yoncfall.vpnlauncher.core.fetchNodes
import com.yoncfall.vpnlauncher.core.loadState
import com.yoncfall.vpnlauncher.core.measureNodeLatency
import com.yoncfall.vpnlauncher.core.newSingBoxConfig
import com.yoncfall.vpnlauncher.core.saveState
import com.yoncfall.vpnlauncher.vpn.VpnRuntime
import com.yoncfall.vpnlauncher.vpn.VpnServiceImpl
import io.nekohasekai.libbox.Libbox
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * MessageBox: заголовок пуст = показывается только текст (как Show(text)).
 * items непусто = режим picker'а установленных приложений (label, package)
 * для исключений вместо текстового окна.
 */
data class GameDialog(
    val title: String,
    val text: String,
    val items: List<Pair<String, String>> = emptyList(),
)

/** Цвет строки статуса (Pal: Text/Danger/Warn/Accent2). */
enum class StatusLevel { TEXT, DANGER, WARN, OK, ACCENT }

data class UiState(
    val subUrl: String = "",
    val nodes: List<JsonObject> = emptyList(),
    val selectedIndex: Int = -1, // одиночное выделение, -1 = авто-тест всех
    val selectedExcl: Int = -1,
    val pings: Map<String, Int> = emptyMap(), // tag -> мс (-2 в процессе)
    val pingRunning: Boolean = false,
    val loadRunning: Boolean = false,
    val connectRunning: Boolean = false,
    val mode: String = "tun",
    val exclusions: List<String> = emptyList(),
    val procInput: String = "",
    val status: String = "Готов",
    val statusLevel: StatusLevel = StatusLevel.TEXT,
    val led: LedState = LedState.OFF,
    val disconnectEnabled: Boolean = false,
    val egress: String = "",
    val egressAccent: Boolean = false,
    val dialog: GameDialog? = null,
    val logVisible: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val stateFile = File(app.filesDir, "state.json")
    private val configFile = File(app.filesDir, "config.json")

    @Volatile
    private var persisted: VpnState = loadState(stateFile)

    private val _state = MutableStateFlow(
        UiState(
            subUrl = persisted.subUrl,
            // mode всегда 'tun': режим прокси на Android недоступен.
            // исключения = пакеты приложений; старые .exe-записи (настольный
            // порт) отбрасываем - они на Android бессмысленны
            exclusions = persisted.appList.filter { PACKAGE_RE.matcher(it).matches() },
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _consent = MutableStateFlow<Intent?>(null)
    /** Системное согласие VpnService.prepare: null - не показывать. */
    val consent: StateFlow<Intent?> = _consent.asStateFlow()

    private var wasConnected = false
    private var wasStarting = false
    private var egressJob: Job? = null

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    init {
        AppLog.sink = { msg -> appendLog(msg) }
        viewModelScope.launch {
            VpnRuntime.state.collect { onRuntime(it) }
        }
        // холодный старт: подхватываем последнюю ссылку (E2E: HiOS убил процесс -
        // список пуст -> connect падал в «Сначала загрузи подписку»); тихо,
        // чтобы оффлайн при старте не показывал диалог
        if (persisted.subUrl.isNotBlank()) {
            loadImpl(quiet = true)
        }
    }

    // ------------------------------------------------------------- действия

    fun onSubUrl(v: String) = _state.update { it.copy(subUrl = v) }

    fun onProcInput(v: String) = _state.update { it.copy(procInput = v) }

    fun selectNode(index: Int) =
        _state.update { it.copy(selectedIndex = if (it.selectedIndex == index) -1 else index) }

    fun selectExcl(index: Int) = _state.update { it.copy(selectedExcl = index) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun showLog() = _state.update { it.copy(logVisible = true) }

    fun hideLog() = _state.update { it.copy(logVisible = false) }

    fun consentShown() {
        _consent.value = null
    }

    /** Режим: 'proxy' на Android недоступен (нет системного прокси). */
    fun selectMode(mode: String) {
        if (mode == "tun") {
            _state.update { it.copy(mode = "tun") }
        } else {
            _state.update {
                it.copy(
                    status = "Системный прокси недоступен на Android - используется TUN",
                    statusLevel = StatusLevel.WARN,
                )
            }
        }
    }

    // ------------------------------------------------------------ загрузка

    fun loadSubscription() = loadImpl(quiet = false)

    /** quiet=true: стартовая автозагрузка - ошибки только статусом, без диалога. */
    private fun loadImpl(quiet: Boolean) {
        val st = _state.value
        if (st.loadRunning) return
        val url = st.subUrl.trim()
        if (url.isEmpty()) {
            if (!quiet) {
                _state.update { it.copy(dialog = GameDialog("", "Вставь ссылку на подписку.")) }
            }
            return
        }
        _state.update { it.copy(loadRunning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fetched = fetchNodes(url)
                _state.update {
                    it.copy(
                        nodes = fetched,
                        subUrl = url,
                        loadRunning = false,
                        status = "Серверов загружено: ${fetched.size}",
                        statusLevel = StatusLevel.OK,
                    )
                }
                save(persisted.copy(subUrl = url))
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        loadRunning = false,
                        status = if (quiet) "Подписка не загрузилась - нажми Загрузить" else "Ошибка загрузки",
                        statusLevel = StatusLevel.WARN,
                        dialog = if (quiet) null else GameDialog("VPN ЛАУНЧЕР BY @YoncFALL", e.message ?: ""),
                    )
                }
            } finally {
                // VPN.ps1:150 - хеш пингов чистится всегда, даже при ошибке;
                // выделение списка сбрасывается Clear'ом только при успехе
                _state.update { it.copy(pings = emptyMap(), selectedIndex = -1) }
            }
        }
    }

    // ---------------------------------------------------------------- пинг

    fun pingAll() {
        val st = _state.value
        if (st.pingRunning) return
        if (st.nodes.isEmpty()) {
            _state.update { it.copy(dialog = GameDialog("", "Сначала загрузи подписку.")) }
            return
        }
        val nodes = st.nodes
        val total = nodes.size
        _state.update {
            it.copy(
                pingRunning = true,
                pings = nodes.associate { n -> n["tag"]!!.jsonPrimitive.content to -2 },
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var done = 0
                for (n in nodes) {
                    val tag = n["tag"]!!.jsonPrimitive.content
                    val ms = measureNodeLatency(n)
                    done++
                    _state.update {
                        it.copy(
                            pings = it.pings + (tag to ms),
                            status = "Проверка пинга: $done из $total",
                            statusLevel = StatusLevel.WARN,
                        )
                    }
                }
                val pings = _state.value.pings
                val ok = pings.values.count { it >= 0 }
                val best = pings.values.filter { it >= 0 }.minOrNull()
                _state.update {
                    it.copy(
                        pingRunning = false,
                        status = "Пинг готов: $ok из $total доступны" +
                            (best?.let { b -> ", лучший $b мс" } ?: ""),
                        statusLevel = if (ok > 0) StatusLevel.OK else StatusLevel.DANGER,
                    )
                }
                AppLog.write("ping done: $ok of $total reachable, best=${best ?: ""} ms")
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        pingRunning = false,
                        status = "Ошибка проверки пинга (см. лог)",
                        statusLevel = StatusLevel.DANGER,
                    )
                }
                AppLog.write("ping ERROR: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ исключения

    fun addExclusion() {
        val st = _state.value
        val v = st.procInput.trim()
        if (v.isEmpty()) return
        if (!PACKAGE_RE.matcher(v).matches()) {
            _state.update {
                it.copy(
                    status = "Не пакет Android (пример: com.whatsapp)",
                    statusLevel = StatusLevel.DANGER,
                )
            }
            return
        }
        if (v in st.exclusions) {
            _state.update { it.copy(status = "$v уже есть в списке", statusLevel = StatusLevel.WARN) }
            return
        }
        val list = st.exclusions + v
        _state.update {
            it.copy(
                exclusions = list,
                procInput = "",
                status = "Исключение добавлено: $v",
                statusLevel = StatusLevel.OK,
            )
        }
        save(persisted.copy(appList = list))
        AppLog.write("exclusion added by user: $v")
    }

    /**
     * Picker установленных приложений для исключений. Android-девиация:
     * настольный порт выбирал запущенные .exe-процессы, здесь список ставится
     * из PackageManager (label + пакет), эффект - addDisallowedApplication.
     */
    fun showAppPicker() {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = installedApps()
            _state.update {
                it.copy(dialog = GameDialog("ВЫБРАТЬ ПРИЛОЖЕНИЕ", "", items = apps))
            }
        }
    }

    /** Тап в picker: пакет уходит сразу в исключения (поле - как ввод руками). */
    fun pickApp(pkg: String) {
        _state.update { it.copy(dialog = null, procInput = pkg) }
        addExclusion()
    }

    private fun installedApps(): List<Pair<String, String>> {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString().trim() to it.activityInfo.packageName }
            .filter { it.first.isNotEmpty() && it.second != app.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    fun deleteExclusion() {
        val st = _state.value
        if (st.selectedExcl !in st.exclusions.indices) return
        val list = st.exclusions.filterIndexed { i, _ -> i != st.selectedExcl }
        _state.update { it.copy(exclusions = list, selectedExcl = -1) }
        save(persisted.copy(appList = list))
    }

    fun clearExclusions() {
        val st = _state.value
        if (st.exclusions.isEmpty()) return
        _state.update {
            it.copy(
                exclusions = emptyList(),
                selectedExcl = -1,
                status = "Список исключений очищен",
                statusLevel = StatusLevel.WARN,
            )
        }
        save(persisted.copy(appList = emptyList()))
    }

    // ----------------------------------------------------------- подключение

    fun connect() {
        val st = _state.value
        if (st.connectRunning || st.disconnectEnabled) return
        if (st.nodes.isEmpty()) {
            // E2E: после пересоздания активности автозагрузка ещё идёт (~40 с) -
            // тап по «Подключиться» получал ошибку; говорим статусом
            if (st.loadRunning) {
                _state.update {
                    it.copy(status = "Подписка ещё грузится - секунду...", statusLevel = StatusLevel.TEXT)
                }
                return
            }
            _state.update { it.copy(dialog = GameDialog("", "Сначала загрузи подписку.")) }
            return
        }
        val tags = selectedTags(st)
        // VPN.ps1:468-472 - сохраняем режим/исключения/ссылку/выделение
        save(
            persisted.copy(
                mode = "tun",
                appList = st.exclusions,
                subUrl = st.subUrl.trim(),
                selected = tags.joinToString(","),
            ),
        )
        // цвет статуса не переназначаем (PS: ForeColor не менялся)
        _state.update {
            it.copy(
                connectRunning = true,
                status = "Генерация конфига...",
                statusLevel = StatusLevel.TEXT,
                led = LedState.BUSY,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                VpnServiceImpl.ensureReady(app)
                var path = buildConfig(st, tags, onlySelected = false)
                var (ok, err) = checkConfig(path)
                if (!ok) {
                    AppLog.write("config check failed: $err")
                    if (tags.isNotEmpty()) {
                        // VPN.ps1:533-540 - повтор только выделенным сервером
                        AppLog.write("retry with selected server only")
                        path = buildConfig(st, tags, onlySelected = true)
                        val retry = checkConfig(path)
                        ok = retry.first
                        err = retry.second
                    }
                    if (!ok) throw ConfigError("Конфиг не прошёл проверку: $err")
                }
                _state.update { it.copy(status = "Запуск sing-box...", statusLevel = StatusLevel.TEXT) }
                val consent = VpnServiceImpl.prepare(app)
                if (consent != null) {
                    _consent.value = consent
                } else {
                    VpnServiceImpl.start(app, path.path, st.exclusions)
                }
            } catch (e: Throwable) {
                AppLog.write("connect ERROR: ${e.message}")
                _state.update {
                    it.copy(
                        connectRunning = false,
                        led = LedState.OFF,
                        status = "Не удалось подключиться",
                        statusLevel = StatusLevel.DANGER,
                        dialog = GameDialog("Ошибка подключения", e.message ?: ""),
                    )
                }
            }
        }
    }

    /** Результат системного согласия (StartActivityForResult). */
    fun onConsentResult(ok: Boolean) {
        _consent.value = null
        if (ok) {
            VpnServiceImpl.start(getApplication(), configFile.path, _state.value.exclusions)
            // дальше статусы придёт от сервиса (STARTING -> CONNECTED)
        } else {
            _state.update {
                it.copy(
                    connectRunning = false,
                    led = LedState.OFF,
                    status = "Отменено - подключение не выполнено",
                    statusLevel = StatusLevel.DANGER,
                )
            }
        }
    }

    fun disconnect() {
        wasConnected = false
        wasStarting = false
        VpnServiceImpl.stop(getApplication())
        stopEgress()
        _state.update {
            it.copy(
                status = "Отключено",
                statusLevel = StatusLevel.TEXT,
                led = LedState.OFF,
                disconnectEnabled = false,
                connectRunning = false,
                egress = "",
                egressAccent = false,
            )
        }
    }

    fun testConfig() {
        val st = _state.value
        if (st.nodes.isEmpty()) {
            _state.update { it.copy(dialog = GameDialog("", "Сначала загрузи подписку.")) }
            return
        }
        val tags = selectedTags(st)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                VpnServiceImpl.ensureReady(getApplication())
                val path = buildConfig(st, tags, onlySelected = false)
                val (ok, err) = checkConfig(path)
                if (ok) {
                    _state.update {
                        it.copy(
                            status = "Конфиг корректен",
                            statusLevel = StatusLevel.OK,
                            dialog = GameDialog("VPN ЛАУНЧЕР BY @YoncFALL", "Конфиг корректен."),
                        )
                    }
                } else {
                    _state.update { it.copy(dialog = GameDialog("Ошибка конфига", err)) }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(dialog = GameDialog("Ошибка", e.message ?: "")) }
            }
        }
    }

    // --------------------------------------------------------------- движок

    private fun onRuntime(s: VpnRuntime.State) {
        when (s) {
            VpnRuntime.State.STARTING -> {
                wasStarting = true
                _state.update {
                    it.copy(status = "Запуск sing-box...", statusLevel = StatusLevel.WARN, led = LedState.BUSY)
                }
            }

            VpnRuntime.State.CONNECTED -> {
                wasConnected = true
                wasStarting = false
                val n = selectedTags(_state.value).size
                val desc = if (n > 0) "$n сервер(а)" else "авто-тест всех"
                _state.update {
                    it.copy(
                        // без 'pid N': движок in-process (отклонение)
                        status = "ПОДКЛЮЧЕНО  |  $desc",
                        statusLevel = StatusLevel.OK,
                        led = LedState.OK,
                        disconnectEnabled = true,
                        connectRunning = false,
                    )
                }
                startEgress()
            }

            VpnRuntime.State.FAILED -> {
                wasConnected = false
                wasStarting = false
                stopEgress()
                val dialogText = VpnRuntime.dialogText
                _state.update {
                    it.copy(
                        status = VpnRuntime.statusText ?: "Не удалось подключиться",
                        statusLevel = StatusLevel.DANGER,
                        led = LedState.ERR,
                        disconnectEnabled = false,
                        connectRunning = false,
                        egress = "",
                        egressAccent = false,
                        dialog = dialogText?.let { t -> GameDialog(VpnRuntime.dialogTitle ?: "", t) },
                    )
                }
            }

            VpnRuntime.State.IDLE -> {
                if (wasConnected || wasStarting) {
                    // чистая остановка (revoke/наш stop), не авария
                    wasConnected = false
                    wasStarting = false
                    stopEgress()
                    _state.update {
                        it.copy(
                            status = "Отключено",
                            statusLevel = StatusLevel.TEXT,
                            led = LedState.OFF,
                            disconnectEnabled = false,
                            egress = "",
                            egressAccent = false,
                        )
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------- egress

    private fun startEgress() {
        if (egressJob?.isActive == true) return
        egressJob = viewModelScope.launch {
            var counter = 0
            while (isActive) {
                delay(10_000)
                counter++
                if (counter % 3 == 1) fetchEgress()
            }
        }
    }

    private fun stopEgress() {
        egressJob?.cancel()
        egressJob = null
    }

    private suspend fun fetchEgress() {
        val ip = withContext(Dispatchers.IO) {
            try {
                val call = http.newCall(
                    Request.Builder().url("https://api.ipify.org?format=json").build(),
                )
                call.execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    EGRESS_IP_RE.find(body)?.groupValues?.get(1)
                }
            } catch (e: Throwable) {
                null
            }
        }
        if (ip != null) {
            _state.update { it.copy(egress = "Внешний IP: $ip", egressAccent = true) }
        } else {
            _state.update { it.copy(egress = "Внешний IP недоступен", egressAccent = false) }
        }
    }

    // ----------------------------------------------------------------- прочее

    private fun selectedTags(st: UiState): List<String> =
        if (st.selectedIndex in st.nodes.indices) {
            listOf(st.nodes[st.selectedIndex]["tag"]!!.jsonPrimitive.content)
        } else {
            emptyList()
        }

    private fun buildConfig(st: UiState, tags: List<String>, onlySelected: Boolean): File =
        newSingBoxConfig(
            nodes = st.nodes,
            selected = tags,
            mode = "tun",
            appList = st.exclusions,
            onlySelected = onlySelected,
            installRoot = getApplication<Application>().filesDir.path,
            path = configFile,
        )

    /** Test-SingBoxConfig заменён на Libbox.checkConfig(json). */
    private fun checkConfig(path: File): Pair<Boolean, String> = try {
        Libbox.checkConfig(path.readText())
        true to ""
    } catch (e: Throwable) {
        false to (e.message ?: "нет вывода")
    }

    private fun save(st: VpnState) {
        synchronized(persistLock) {
            persisted = st
            saveState(st, stateFile)
        }
    }

    private val persistLock = Any()

    companion object {
        /** Формат строки write_log (core.ps1:25): 'yyyy-MM-dd HH:mm:ss  msg'. */
        private fun appendLog(msg: String) {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val line = "$ts  $msg"
            synchronized(logBuffer) {
                logBuffer.addLast(line)
                while (logBuffer.size > 500) logBuffer.removeFirst()
                _logLines.value = logBuffer.toList()
            }
        }

        private val logBuffer = ArrayDeque<String>()
        private val _logLines = MutableStateFlow<List<String>>(emptyList())

        /** Журнал в памяти (заменяет файл 1.0.6; обрезка до 500 строк). */
        val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

        // Android-девиация вместо VPN.ps1:256 (настольные исключения -
        // имена .exe-процессов): здесь исключение = пакет приложения
        // (com.example.app), эффект - VpnService.addDisallowedApplication.
        // Точка обязательна, .exe отсекается явно (настольные имена на
        // Android бессмысленны и молча игнорировались бы системой).
        // internal: проверяется тестом PackageNameTest.
        internal val PACKAGE_RE: Pattern =
            Pattern.compile(
                "^(?!.*\\.exe$)[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$",
                Pattern.CASE_INSENSITIVE,
            )

        private val EGRESS_IP_RE = Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"")
    }
}
