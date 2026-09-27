package com.cliplink

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cliplink.shizuku.ShizukuHelper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 前台服务，承载剪切板监听的完整生命周期。
 *
 * 事件流：Shizuku UserService 的 `dispatchPrimaryClipChanged` ->
 * 回调 -> 过滤条件 -> 提取网址 -> 去重 -> 历史记录 -> 通知。
 *
 * ## 双通道设计
 * 主通道是事件驱动的监听器（低延迟、无轮询开销）。但监听注册存在两个已知的
 * 失效场景：`system_server` 重启会丢失注册、部分机型/OEM 会让注册静默失败。
 * 因此这里额外运行一个低速看门狗轮询（默认 3 秒）作为保险：
 * 它只负责发现"剪切板变了但监听器没通知"，从而让功能不会静默失效。
 */
class ClipboardMonitorService : Service(), ShizukuHelper.StateListener {

    private lateinit var prefs: Prefs
    private lateinit var notifications: NotificationHelper
    private lateinit var repository: LinkRepository
    private lateinit var shizuku: ShizukuHelper

    /** 去重与保序都在这条单线程上做，避免并发写历史记录。 */
    private val worker = Executors.newSingleThreadExecutor()

    /** 看门狗轮询线程。只在监听未就绪时才真正读剪切板。 */
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var lastUrl: String? = null

    @Volatile
    private var lastUrlAt: Long = 0L

    @Volatile
    private var ready = false

    /** 上一次看到的剪切板内容指纹，用于轮询时判断是否变化。 */
    @Volatile
    private var lastClipFingerprint: Int = 0

    /** 监听注册是否成功。失败时看门狗承担全部检测职责。 */
    @Volatile
    private var listenerRegistered = false

    /**
     * Shizuku 回调运行在 Binder 线程，且可能连续快速触发，
     * 因此只做最轻的转发，重活交给 [worker]。
     *
     * 第一个参数是剪切板内容，第二个是错误信息，二者互斥。
     */
    private val onShizukuEvent: (ClipData?, String?) -> Unit = { clip, error ->
        when {
            error != null -> {
                Log.w(TAG, "UserService 错误：$error")
                worker.execute { notifications.showShizukuProblem(error) }
            }
            clip != null -> {
                val text = clip.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(this)?.toString()
                if (!text.isNullOrBlank()) worker.execute { handleText(text) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        notifications = NotificationHelper(this)
        repository = LinkRepository(this)
        shizuku = ShizukuHelper(this)
        shizuku.addListener(this)
        lastUrl = prefs.lastUrl
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                notifications.cancelResult()
                return START_STICKY
            }
            ACTION_STOP -> {
                prefs.monitoringEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        prefs.monitoringEnabled = true
        // 必须先进入前台，否则 Android 8+ 会直接抛异常。
        startForegroundSafely()
        connectShizuku()
    }

    private fun startForegroundSafely() {
        // 用当前真实状态构造首条通知，避免先闪一条"已暂停"再被覆盖。
        val notification = notifications.buildStatus(
            ready = false,
            reason = getString(R.string.status_connecting),
            captured = prefs.capturedCount
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.ID_STATUS, notification, foregroundServiceType())
        } else {
            startForeground(NotificationHelper.ID_STATUS, notification)
        }
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    private fun connectShizuku() {
        val state = shizuku.currentState()
        when (state) {
            ShizukuHelper.State.READY -> {
                // 复用一个已存在的 UserService 绑定；回调在这里注入。
                shizuku.bindUserService(onShizukuEvent)
            }
            ShizukuHelper.State.NO_PERMISSION -> {
                // 前台服务里不能弹权限框，交给用户回到主界面处理。
                onUnavailable(getString(R.string.reason_need_permission))
            }
            ShizukuHelper.State.NOT_RUNNING -> {
                onUnavailable(getString(R.string.reason_shizuku_not_running))
            }
            ShizukuHelper.State.NOT_INSTALLED -> {
                onUnavailable(getString(R.string.reason_shizuku_not_installed))
            }
        }
    }

    // ------------------------------------------------------------ 状态回调

    override fun onReady() {
        ready = true
        listenerRegistered = shizuku.isListenerRegistered()
        if (listenerRegistered) {
            Log.i(TAG, "事件监听已就绪，使用事件驱动通道")
        } else {
            // 绑定成功但监听未注册（例如 Android 14+ 的 deviceId 参数不兼容，
            // 或 system_server 静默拒绝了注册）。此时看门狗承担全部检测职责。
            Log.w(TAG, "监听未注册成功，改由看门狗轮询承担检测；原因=${shizuku.registerError()}")
        }
        notifications.showStatus(ready = true, captured = prefs.capturedCount)
        startWatchdog()
    }

    override fun onUnavailable(reason: String) {
        ready = false
        notifications.showStatus(ready = false, reason = reason, captured = prefs.capturedCount)
    }

    // ------------------------------------------------------------ 看门狗

    /**
     * 启动低速轮询作为保险。
     *
     * 只在 Shizuku 就绪时运行：没有 shell 身份就读不到剪切板，轮询没有意义。
     * 已注册监听时用较低频率（[WATCHDOG_INTERVAL_WITH_LISTENER_MS]），
     * 未注册时用较高频率（[WATCHDOG_INTERVAL_NO_LISTENER_MS]）承担主要检测职责。
     */
    private fun startWatchdog() {
        stopWatchdog()
        val interval = if (listenerRegistered) {
            WATCHDOG_INTERVAL_WITH_LISTENER_MS
        } else {
            WATCHDOG_INTERVAL_NO_LISTENER_MS
        }
        Log.i(TAG, "启动看门狗，间隔 ${interval}ms（监听注册=$listenerRegistered）")

        val future = watchdog.scheduleWithFixedDelay(
            { runCatching { watchdogTick() }.onFailure { Log.w(TAG, "看门狗异常", it) } },
            WATCHDOG_INITIAL_DELAY_MS,
            interval,
            TimeUnit.MILLISECONDS
        )
        watchdogQueue.add(future)
    }

    private fun stopWatchdog() {
        // scheduleWithFixedDelay 返回的 Future 需要取消，否则重复调用会叠加任务。
        watchdogQueue.forEach { runCatching { it.cancel(false) } }
        watchdogQueue.clear()
    }

    private val watchdogQueue = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.ScheduledFuture<*>>()

    /**
     * 一次轮询：读剪切板摘要并比对指纹，变了就交给 [handleText]。
     *
     * 用 `readPrimaryClip` 而不是摘要接口，因为 UserService 侧已在 shell 身份下，
     * 读取不会产生系统的"已粘贴"提示（该提示只针对普通应用）。
     */
    private fun watchdogTick() {
        if (!ready) return
        if (!conditionSatisfied()) return

        val clip = runCatching { shizuku.readClipboardOnce() }.getOrNull() ?: return
        if (clip.itemCount == 0) return

        val text = runCatching {
            clip.getItemAt(0).coerceToText(this).toString()
        }.getOrNull() ?: return

        if (text.isBlank()) return

        val fingerprint = text.hashCode()
        if (fingerprint == lastClipFingerprint) return
        lastClipFingerprint = fingerprint

        // 监听器正常工作时，事件通道已经处理过这次变化；
        // handleText 内部的去重会挡掉重复提醒，这里无需额外判断。
        worker.execute { handleText(text) }
    }

    // ------------------------------------------------------------ 事件处理

    private fun handleText(text: String) {
        if (!conditionSatisfied()) return

        val url = UrlExtractor.extractFirst(text) ?: return
        if (isIgnored(url)) return
        if (isDuplicate(url)) return

        lastUrl = url
        lastUrlAt = System.currentTimeMillis()
        prefs.lastUrl = url

        repository.add(url, source = "clipboard")

        prefs.capturedCount = prefs.capturedCount + 1
        notifications.showStatus(ready = ready, captured = prefs.capturedCount)

        if (prefs.autoOpen) {
            openDirectly(url)
        } else {
            notifications.showLinkFound(url)
        }
    }

    /** 同一链接在冷却期内不重复提醒。 */
    private fun isDuplicate(url: String): Boolean {
        if (url != lastUrl) return false
        return System.currentTimeMillis() - lastUrlAt < DEDUP_WINDOW_MS
    }

    private fun isIgnored(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return prefs.ignoredDomains.any { ignored ->
            host.equals(ignored, ignoreCase = true) || host.endsWith(".$ignored", ignoreCase = true)
        }
    }

    /** 「仅 Wi-Fi」/「仅充电」等省电条件的判断。 */
    private fun conditionSatisfied(): Boolean {
        if (prefs.wifiOnly && !isOnWifi()) return false
        if (prefs.chargingOnly && !isCharging()) return false
        return true
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isCharging(): Boolean {
        // 注册 null receiver 只为读取当前粘性广播值。
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /** 「自动打开」模式：直接拉起浏览器，不经过通知确认。 */
    private fun openDirectly(url: String) {
        val intent = Intent(this, OpenLinkActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(android.net.Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure {
                // 后台启动 Activity 受限时退回通知提醒，不让功能静默失效。
                notifications.showLinkFound(url)
            }
    }

    // ------------------------------------------------------------ 生命周期

    override fun onDestroy() {
        isRunning = false
        stopWatchdog()
        shizuku.removeListener(this)
        shizuku.unbind()
        watchdog.shutdownNow()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ClipboardMonitor"

        /**
         * 服务是否真的在运行。
         *
         * 界面用它判断状态，而**不能只看 [Prefs.monitoringEnabled]**：
         * 那只是"用户上次的意图"记录。服务被系统回收时 `onDestroy` 不保证被调用，
         * 该记录会停留在 true，导致界面显示"监测中"而实际没有监测。
         *
         * 服务与界面在同一进程，因此进程内的静态标记是可靠的。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 同一链接的提醒冷却时间。 */
        private const val DEDUP_WINDOW_MS = 3000L

        /** 看门狗首次执行前的延迟，等监听器先完成注册。 */
        private const val WATCHDOG_INITIAL_DELAY_MS = 2000L

        /**
         * 监听已注册时的轮询间隔。
         * 此时轮询只是保险，频率可以很低，几乎不耗电。
         */
        private const val WATCHDOG_INTERVAL_WITH_LISTENER_MS = 5000L

        /**
         * 监听未注册时的轮询间隔。
         * 此时轮询是唯一检测手段，需要在及时性与耗电之间折中。
         */
        private const val WATCHDOG_INTERVAL_NO_LISTENER_MS = 1500L

        const val ACTION_START = "com.cliplink.action.START"
        const val ACTION_STOP = "com.cliplink.action.STOP"
        const val ACTION_DISMISS = "com.cliplink.action.DISMISS"

        fun start(context: Context) {
            // 立即置位：startForegroundService 是异步的，界面紧随其后就会查询状态。
            // 若不预先置位，用户点"开始监测"后会先闪一下"已停止"。
            isRunning = true
            val intent = Intent(context, ClipboardMonitorService::class.java)
                .setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                // 启动失败（如后台启动受限），回滚标记，避免界面误报为运行中。
                isRunning = false
            }
        }

        fun stop(context: Context) {
            isRunning = false
            val intent = Intent(context, ClipboardMonitorService::class.java)
                .setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
