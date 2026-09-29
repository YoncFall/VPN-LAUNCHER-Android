// VPN-сервис: согласие системы -> CommandServer(libbox) -> TUN.
// Порт жизненного цикла SFA VPNService + BoxService под libbox 1.14.1:
//   - старт: startForeground в onStartCommand (5-сек лимит; тип FGS берётся
//     из манифеста — systemExempted, как в SFA), затем подъём CommandServer
//     и startOrReloadService в single-thread executor;
//   - Libbox.setup(SetupOptions) — как в Application.onCreate SFA (пути,
//     fixAndroidStack по схеме SFA Bugs.fixAndroidStack);
//   - стоп: ACTION_STOP / onRevoke / onDestroy -> closeService() + close()
//     (идемпотентно, в порядке очереди executor);
//   - уведомление статическое: у SFA оно динамическое через CommandClient
//     (скорость ↑↓ в строке) — у нас нет UI-подписки, отклонение.
//   - kill switch (отклонение от SFA): авария движка при живом tun сервис
//     НЕ останавливает - туннель продолжает гасить трафик (VpnRuntime
//     .killSwitch), иначе он ушёл бы напрямую без VPN; снимается только
//     ручной «ОТКЛЮЧИТЬ» (closeEngine). Процесс, убитый системой, снимает
//     маршруты сам - предел Android (та же особенность у SFA).
// Always-on VPN / системный перезапуск (intent == null) возобновляет
// последний конфиг из SharedPreferences (пишется в start()).
//
// Согласие на VPN (VpnService.prepare) выполняет UI до вызова start()
// (см. этап 7).
package com.yoncfall.vpnlauncher.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yoncfall.vpnlauncher.BuildConfig
import com.yoncfall.vpnlauncher.MainActivity
import com.yoncfall.vpnlauncher.R
import com.yoncfall.vpnlauncher.core.AppLog
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VpnServiceImpl : VpnService() {

    companion object {
        private const val TAG = "VpnServiceImpl"
        const val ACTION_START = "com.yoncfall.vpnlauncher.vpn.START"
        const val ACTION_STOP = "com.yoncfall.vpnlauncher.vpn.STOP"
        const val EXTRA_CONFIG = "configPath"
        const val EXTRA_EXCLUDE = "excludePackages"
        private const val PREFS = "vpn"
        private const val KEY_LAST_CONFIG = "lastConfig"
        private const val KEY_LAST_EXCLUDE = "lastExclude"
        private const val NOTIFICATION_ID = 10
        private const val CHANNEL_ID = "vpn"

        @Volatile
        private var libboxReady = false

        // сервис и приложение в одном процессе: прямой вызов stopAll снимает
        // FGS (stopForeground), без чего система держит сервис ConnectionRecord'ом
        // "CR FGS" и stopService не приводит к onDestroy (E2E: туннель жил)
        @Volatile
        private var instance: VpnServiceImpl? = null

        /** Согласие системы: null = уже выдано, иначе Intent для startActivityForResult. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)

        /** Libbox.setup (idempotent) для проверки конфига из UI до старта сервиса. */
        fun ensureReady(context: Context) = ensureSetup(context.applicationContext)

        /**
         * Запуск туннеля: пишет последний конфиг (для always-on) и пакеты
         * исключений (для addDisallowedApplication) и поднимает сервис.
         */
        fun start(context: Context, configPath: String, excludePackages: List<String> = emptyList()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_CONFIG, configPath)
                .putStringSet(KEY_LAST_EXCLUDE, excludePackages.toSet())
                .apply()
            val intent = Intent(context, VpnServiceImpl::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, configPath)
                .putStringArrayListExtra(EXTRA_EXCLUDE, ArrayList(excludePackages))
            ContextCompat.startForegroundService(context, intent)
        }

        /** Остановка: прямой вызов stopAll из того же процесса (E2E: один лишь
         *  stopService оставлял isForeground=true - система удерживала сервис
         *  FGS-связью, onDestroy не вызывался, туннель жил при «Отключено»).
         *  Если сервиса нет - страховка stopService. */
        fun stop(context: Context) {
            AppLog.write("stop: запрошена остановка сервиса")
            val inst = instance
            if (inst != null) {
                runCatching { inst.stopAll() }
                    .onFailure { AppLog.write("stop ERROR: ${it.message}") }
            } else {
                runCatching {
                    context.stopService(Intent(context, VpnServiceImpl::class.java))
                }.onFailure {
                    AppLog.write("stop ERROR: ${it.message}")
                }
            }
        }

        // Libbox.setup — один раз на процесс (пути, логи, краш-хендлеры)
        private fun ensureSetup(context: Context) {
            if (libboxReady) return
            synchronized(this) {
                if (libboxReady) return
                runCatching { Libbox.setLocale(Locale.getDefault().toLanguageTag()) }
                Libbox.touch()
                val base = context.filesDir
                base.mkdirs()
                val working = context.getExternalFilesDir(null) ?: base
                working.mkdirs()
                val temp = context.cacheDir
                temp.mkdirs()
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                @Suppress("DEPRECATION")
                val options = SetupOptions().apply {
                    basePath = base.path
                    workingPath = working.path
                    tempPath = temp.path
                    // фикс golang/go#68760 — схема SFA Bugs.fixAndroidStack
                    fixAndroidStack =
                        BuildConfig.DEBUG ||
                            Build.VERSION.SDK_INT in Build.VERSION_CODES.N..Build.VERSION_CODES.N_MR1 ||
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    logMaxLines = 3000
                    debug = BuildConfig.DEBUG
                    crashReportSource = "VpnServiceImpl"
                    appVersion = packageInfo.versionCode.toString()
                    appMarketingVersion = packageInfo.versionName ?: "0.1.0"
                }
                Libbox.setup(options)
                libboxReady = true
            }
        }
    }

    private val engineLock = Any()
    private lateinit var executor: ExecutorService

    /** Идёт закрытие (onDestroy): остановку движка не считаем аварией. */
    @Volatile
    private var closing = false

    @Volatile
    private var commandServer: CommandServer? = null

    @Volatile
    private var configPath: String? = null

    /**
     * Пакеты приложений, исключённых из VPN (из UI, см. start()). Читаются
     * в startEngine из prefs (путь и для системного always-on старта),
     * применяются в PlatformBridge.openTun = addDisallowedApplication.
     */
    @Volatile
    var excludedPackages: List<String> = emptyList()
        private set

    /** Дескриптор TUN из establish(): закрывается при остановке (паттерн SFA). */
    @Volatile
    var tunDescriptor: ParcelFileDescriptor? = null

    private val notificationManager: NotificationManager?
        get() = getSystemService(NotificationManager::class.java)

    val connectivity: ConnectivityManager
        get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // ------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vpn-engine").apply { isDaemon = true }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_CONFIG)
                    ?: getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_LAST_CONFIG, null)
                if (path.isNullOrBlank() || !File(path).exists()) {
                    failStart("конфиг не найден: $path")
                    return START_NOT_STICKY
                }
                showForeground(getString(R.string.notif_starting))
                startEngine(path)
            }

            ACTION_STOP -> stopAll()

            else -> {
                // системный старт (always-on / перезапуск): последний конфиг
                val path = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_CONFIG, null)
                if (path.isNullOrBlank() || !File(path).exists()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                showForeground(getString(R.string.notif_starting))
                startEngine(path)
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // пользователь отключил VPN в настройках системы
        Log.i(TAG, "revoked by system")
        AppLog.write("VPN отозван системой")
        stopAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        closing = true
        AppLog.write("onDestroy: сервис уничтожен")
        closeEngine()
        executor.shutdown()
    }

    /** Закрытие движка: идемпотентно, ставится в очередь executor. */
    private fun closeEngine() {
        if (closing) return
        closing = true
        AppLog.write("остановка: закрытие движка запрошено")
        // single-thread executor: закрытие встанет ПОСЛЕ текущей операции старта
        executor.execute {
            AppLog.write("остановка: шаг 1/4 - закрытие tun-дескриптора")
            // pfd.close() убивает tun-интерфейс -> система отвязывает сервис
            // (SFA-порядок: сначала fd, потом closeService/close)
            tunDescriptor?.let { pfd -> runCatching { pfd.close() } }
            tunDescriptor = null
            AppLog.write("остановка: шаг 2/4 - монитор сети")
            NetworkMonitor.setListener(null, connectivity)
            val server = synchronized(engineLock) {
                val current = commandServer
                commandServer = null
                current
            }
            if (server != null) {
                AppLog.write("остановка: шаг 3/4 - closeService")
                runCatching { server.closeService() }
                    .onFailure {
                        runCatching { server.setError("android: close service: ${it.message}") }
                    }
                AppLog.write("остановка: шаг 4/4 - close")
                runCatching { server.close() }
                Log.i(TAG, "engine closed")
                AppLog.write("движок остановлен")
            } else {
                AppLog.write("остановка: движок уже снят")
            }
            // чистая остановка -> IDLE; FAILED остаётся (текст ошибки в UI)
            val st = VpnRuntime.state.value
            if (st == VpnRuntime.State.STARTING || st == VpnRuntime.State.CONNECTED) {
                VpnRuntime.state.value = VpnRuntime.State.IDLE
            }
            // tun закрыт -> kill switch снят, чистое состояние (после ручной
            // «ОТКЛЮЧИТЬ» UI уже показывает «Отключено»)
            if (VpnRuntime.killSwitch) {
                VpnRuntime.killSwitch = false
                VpnRuntime.state.value = VpnRuntime.State.IDLE
            }
        }
    }

    // -------------------------------------------------------------- движок

    private fun startEngine(path: String) {
        executor.execute {
            // переиспользование сервиса после остановки: сбрасываем флаг закрытия
            closing = false
            // исключения приложений: источник - prefs (см. start())
            excludedPackages = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_LAST_EXCLUDE, emptySet())
                .orEmpty()
                .toList()
            if (excludedPackages.isNotEmpty()) {
                AppLog.write("excluded apps: ${excludedPackages.joinToString(", ")}")
            }
            synchronized(engineLock) {
                if (commandServer != null) return@execute // уже поднят
                VpnRuntime.state.value = VpnRuntime.State.STARTING
                try {
                    ensureSetup(applicationContext)
                    val server = CommandServer(
                        EngineHandler(this@VpnServiceImpl),
                        PlatformBridge(this@VpnServiceImpl),
                    )
                    server.start()
                    commandServer = server
                } catch (e: Throwable) {
                    Log.e(TAG, "startCommandServer: ${e.message}", e)
                    AppLog.write("startCommandServer: ${e.message}")
                    failStart(e.message ?: "startCommandServer")
                    return@execute
                }
            }
            try {
                configPath = path
                val config = File(path).readText()
                val server = commandServer ?: return@execute
                server.startOrReloadService(config, OverrideOptions())
                if (VpnRuntime.killSwitch) {
                    // движок запросил остановку во время старта: kill switch
                    // уже активен, не перетираем FAILED на CONNECTED
                    return@execute
                }
                Log.i(TAG, "service started")
                AppLog.write("туннель поднят")
                updateNotification(getString(R.string.notif_started))
                VpnRuntime.state.value = VpnRuntime.State.CONNECTED
            } catch (e: Throwable) {
                Log.e(TAG, "startOrReloadService: ${e.message}", e)
                AppLog.write("startOrReloadService: ${e.message}")
                runCatching { commandServer?.setError("android: start service: ${e.message}") }
                failStart(e.message ?: "start service")
            }
        }
    }

    /** Перечитать текущий конфиг (вызывает движок: serviceReload). */
    fun engineReload() {
        val path = configPath ?: return
        executor.execute {
            val server = commandServer ?: return@execute
            runCatching {
                val config = File(path).readText()
                server.startOrReloadService(config, OverrideOptions())
                AppLog.write("конфиг перезагружен")
            }.onFailure {
                runCatching { server.setError("android: reload: ${it.message}") }
                AppLog.write("reload: ${it.message}")
            }
        }
    }

    /** Остановка по запросу движка (serviceStop). */
    fun engineStopRequested() {
        AppLog.write("движок запросил остановку")
        if (closing) {
            // штатная остановка (наш stop/onDestroy): tun закроет closeEngine
            return
        }
        // kill switch: движок упал при живом tun - НЕ закрываем сервис,
        // иначе трафик ушёл бы напрямую, без VPN (утечка IP и данных).
        // TUN без читателя держит маршруты и гасит пакеты, пока пользователь
        // не нажмёт «ОТКЛЮЧИТЬ» (там снимается и флаг killSwitch).
        AppLog.write("kill switch: движок упал, туннель блокирует трафик")
        VpnRuntime.statusText =
            "Соединение оборвалось - трафик заблокирован. Нажми «ОТКЛЮЧИТЬ»"
        VpnRuntime.dialogTitle = null
        VpnRuntime.dialogText = null
        VpnRuntime.killSwitch = true
        VpnRuntime.state.value = VpnRuntime.State.FAILED
        updateNotification(getString(R.string.notif_blocked))
    }

    private fun stopAll() {
        // E2E: система держит сервис связью act=android.net.VpnService, пока
        // жив tun (IntentBindRecord в dumpsys); stopSelf при живом tun не ведёт
        // к onDestroy - взаимоблокировка. Порядок: снять FGS, ЗАКРЫТЬ движок
        // (tun снимется -> система отвяжется), и только потом stopSelf.
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        closeEngine()
        stopSelf()
    }

    private fun failStart(message: String) {
        // сообщение уже продублировано в logcat
        Log.e(TAG, "start failed: $message")
        AppLog.write("connect ERROR: $message")
        VpnRuntime.dialogTitle = "Ошибка подключения"
        VpnRuntime.dialogText = message
        if (tunDescriptor != null) {
            // kill switch: tun уже поднят (openTun случился до падения) -
            // сервис не гасим, туннель продолжает блокировать трафик
            AppLog.write("kill switch: tun поднят, трафик заблокирован")
            VpnRuntime.statusText =
                "Не удалось подключиться - трафик заблокирован. Нажми «ОТКЛЮЧИТЬ»"
            VpnRuntime.killSwitch = true
            VpnRuntime.state.value = VpnRuntime.State.FAILED
            updateNotification(getString(R.string.notif_blocked))
            return
        }
        // туннеля нет - утекать нечему: гасим сервис, чтобы не висела
        // foreground-нотификация с ошибкой
        VpnRuntime.statusText = "Не удалось подключиться"
        VpnRuntime.killSwitch = false
        VpnRuntime.state.value = VpnRuntime.State.FAILED
        stopAll()
    }

    // ---------------------------------------------------------- уведомления

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    private fun showForeground(text: String) {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateNotification(text: String) {
        createChannel()
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** Уведомления движка (sendNotification): канал на каждый typeID. */
    fun postEngineNotification(notification: io.nekohasekai.libbox.Notification) {
        val channel = "engine-${notification.typeID}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager?.createNotificationChannel(
                NotificationChannel(
                    channel,
                    notification.typeName ?: getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(notification.title ?: "")
            .setContentText(notification.body ?: "")
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        // notification.openURL не открываем — этап 7 (документировано)
        notificationManager?.notify(notification.identifier, notification.typeID, builder.build())
    }

    fun cancelEngineNotification(identifier: String, typeID: Int) {
        notificationManager?.cancel(identifier, typeID)
    }

    // ----------------------------------------------------------------- прочее

    /** Builder TUN-интерфейса для PlatformBridge.openTun (inner-класс VpnService). */
    fun newTunBuilder(): Builder = Builder()
}
