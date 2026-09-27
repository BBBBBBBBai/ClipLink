package com.cliplink.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shizuku 状态管理与 UserService 绑定。
 *
 * 把 Shizuku 的可用性判断、权限请求、UserService 生命周期收敛到这里，
 * 让上层（前台服务 / Activity）只关心"是否有可用监听"这一件事。
 */
class ShizukuHelper(private val context: Context) {

    /** 上层通过它感知状态变化。 */
    interface StateListener {
        /** 监听已就绪，可以接收剪切板事件。 */
        fun onReady()

        /** 监听不可用，[reason] 用于界面提示。 */
        fun onUnavailable(reason: String)
    }

    /** 当前状态快照。 */
    enum class State {
        /** 未安装 Shizuku。 */
        NOT_INSTALLED,

        /** 已安装但服务未启动（重启后需手动启动）。 */
        NOT_RUNNING,

        /** 服务在跑，但本应用未获授权。 */
        NO_PERMISSION,

        /** 就绪。 */
        READY
    }

    private val listeners = CopyOnWriteArrayList<StateListener>()

    @Volatile
    private var proxy: ClipboardServiceProxy? = null

    @Volatile
    private var callback: ClipboardCallback? = null

    private var bound = false

    /** 绑定成功/断开时的通知，由前台服务注入以驱动通知状态更新。 */
    @Volatile
    var onServiceConnected: (() -> Unit)? = null

    @Volatile
    var onServiceDisconnected: (() -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                notifyUnavailable("UserService 连接失败")
                return
            }
            val p = ClipboardServiceProxy(service)
            proxy = p
            bound = true

            // 只在调用方提供了回调时才注册。界面侧的只读绑定
            // （bindReadOnly）不会进入这里，因此不会覆盖服务侧的回调。
            callback?.let { cb ->
                runCatching { p.setListener(cb) }
                    .onFailure { notifyUnavailable("注册监听失败：${it.message}") }
            }
            onServiceConnected?.invoke()
            // 只读绑定的等待方（界面诊断）在此被唤醒；消费一次后清空。
            pendingConnectCallback?.let { cb ->
                pendingConnectCallback = null
                runCatching { cb() }
            }
            notifyReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            proxy = null
            bound = false
            // 连接断开时唤醒等待方，避免其永久等待。
            pendingConnectCallback?.let { cb ->
                pendingConnectCallback = null
                runCatching { cb() }
            }
            onServiceDisconnected?.invoke()
            notifyUnavailable("UserService 已断开")
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                notifyUnavailable("未授予 Shizuku 权限")
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder 已就绪")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder 已断开")
        proxy = null
        bound = false
        notifyUnavailable("Shizuku 服务已停止")
    }

    // ------------------------------------------------------------ 状态判定

    /** 探测当前可用状态。不触发权限请求。 */
    fun currentState(): State = when {
        !isInstalled() -> State.NOT_INSTALLED
        !isBinderAlive() -> State.NOT_RUNNING
        !hasPermission() -> State.NO_PERMISSION
        else -> State.READY
    }

    /** Shizuku 应用是否已安装。 */
    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Shizuku 服务是否在运行。 */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已获得本应用的 Shizuku 授权。 */
    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 是否已成功绑定 UserService 并注册监听。 */
    fun isReady(): Boolean = bound && proxy != null

    /** 注册状态监听。 */
    fun addListener(listener: StateListener) {
        if (listeners.none { it === listener }) listeners.add(listener)
    }

    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

    /**
     * 请求权限并绑定服务。已有权限时直接绑定。
     *
     * @return 是否已具备权限（true 表示随后会走绑定流程，不代表绑定已完成）
     */
    fun requestPermissionAndBind(): Boolean {
        if (!isBinderAlive()) {
            notifyUnavailable("Shizuku 服务未运行")
            return false
        }
        return if (hasPermission()) {
            bindUserService()
            true
        } else {
            runCatching {
                Shizuku.addRequestPermissionResultListener(permissionListener)
                Shizuku.requestPermission(REQUEST_CODE)
            }.onFailure { notifyUnavailable("请求权限失败：${it.message}") }
            false
        }
    }

    /**
     * 以**只读**方式绑定 UserService：建立连接但**不注册回调**。
     *
     * 用于界面查询状态（例如诊断区读取"监听是否已注册"）。
     * 绝不能走 [bindUserService]——那条路径会调用 `setListener`，
     * 而 UserService 侧只持有**单个**回调，界面绑定会覆盖掉前台服务
     * 正在使用的回调，导致监测静默失效。
     *
     * @param onConnected 连接就绪时回调（已在绑定状态则立即回调）。
     *        调用方据此驱动后续查询，避免用 sleep 猜测绑定耗时。
     * @return 是否已发起绑定或本就已绑定；false 表示 Shizuku 不可用
     */
    fun bindReadOnly(onConnected: (() -> Unit)? = null): Boolean {
        if (bound) {
            onConnected?.invoke()
            return true
        }
        if (!isBinderAlive() || !hasPermission()) return false

        pendingConnectCallback = onConnected

        return runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            true
        }.getOrElse { false }
    }

    /** 只读绑定的待触发回调，连接成功后消费一次。 */
    @Volatile
    private var pendingConnectCallback: (() -> Unit)? = null

    /**
     * 绑定 UserService，并把剪切板回调注册进去。
     *
     * @param onEvent 收到剪切板事件或错误时的回调，运行在 Binder 线程
     */
    fun bindUserService(onEvent: ((android.content.ClipData?, String?) -> Unit)? = null) {
        if (onEvent != null) callback = ClipboardCallback(onEvent)

        if (bound) {
            // 已绑定，只需更新回调。注意：未传 onEvent 时不应改动现有回调，
            // 否则界面侧的绑定会清掉服务侧的回调。
            if (onEvent != null) {
                callback?.let { cb -> proxy?.let { runCatching { it.setListener(cb) } } }
            }
            notifyReady()
            return
        }

        if (!isBinderAlive()) {
            notifyUnavailable("Shizuku 服务未运行")
            return
        }
        if (!hasPermission()) {
            notifyUnavailable("缺少 Shizuku 权限")
            return
        }

        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        }.onFailure { notifyUnavailable("绑定 UserService 失败：${it.message}") }
    }

    /**
     * 解除**本地**绑定，但保留 UserService 进程。
     *
     * 用于 Activity 退出等场景：多个组件（前台服务、界面）可能同时绑定同一个
     * daemon UserService，任何一个解除绑定都不应影响其它组件正在使用的监听。
     * 真正销毁进程请用 [destroyUserService]。
     */
    fun unbindLocal() {
        if (bound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, false) }
            bound = false
        }
        proxy = null
    }

    /**
     * 注销监听并销毁 UserService 进程。
     *
     * 只在确认不再需要监测时调用（例如用户停止服务），否则会中断正在工作的监听。
     */
    fun unbind() {
        // 先注销监听，避免系统继续向已失效的 Binder 派发事件。
        proxy?.let { runCatching { it.setListener(null) } }
        runCatching { proxy?.destroy() }
        if (bound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
            bound = false
        }
        proxy = null
    }

    /** 主动读一次剪切板。仅在 shell 身份下有效，失败返回 null。 */
    fun readClipboardOnce(): android.content.ClipData? =
        runCatching { proxy?.readPrimaryClip() }.getOrNull()

    /**
     * 读取剪切板并区分「空」与「失败」。
     *
     * 状态检测必须用这个方法而不是 [readClipboardOnce]：后者的 `null`
     * 既表示读取失败也表示剪切板为空，会把正常状态误报为故障。
     */
    fun readClipboardDetailed(): ClipboardReadResult {
        val p = proxy ?: return ClipboardReadResult.Failure
        return runCatching { p.readPrimaryClipDetailed() }
            .getOrDefault(ClipboardReadResult.Failure)
    }

    /**
     * 查询 UserService 侧监听是否注册成功。
     *
     * 用于诊断"已授权但回调不触发"的情况——例如 Android 14+ 的 `deviceId`
     * 参数无效会导致系统静默拒绝注册。
     */
    fun isListenerRegistered(): Boolean =
        runCatching { proxy?.isRegistered() == true }.getOrDefault(false)

    /** 查询注册失败的具体原因，成功注册时返回 null。 */
    fun registerError(): String? =
        runCatching { proxy?.getRegisterError() }.getOrNull()

    private fun notifyReady() = listeners.forEach { runCatching { it.onReady() } }

    private fun notifyUnavailable(reason: String) =
        listeners.forEach { runCatching { it.onUnavailable(reason) } }

    private val userServiceArgs: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ClipboardUserService::class.java.name)
        )
            // daemon=true：UserService 常驻，绑定结束后不随调用方销毁。
            // 剪切板监听需要长期存活，若用 daemon=false 会被系统随时回收。
            .daemon(true)
            .processNameSuffix("clipboard")
            .debuggable(false)
            .version(VERSION)

    private companion object {
        const val TAG = "ShizukuHelper"
        const val REQUEST_CODE = 1001
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val VERSION = 1
    }
}
