package com.cliplink.shizuku

import android.annotation.SuppressLint
import android.content.ClipData
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 运行在 Shizuku 提供的 shell 身份（uid 2000）下的剪切板监听服务。
 *
 * ## 为什么必须走 Shizuku
 * Android 10 起 [android.content.ClipboardManager] 对后台应用返回 null，且**无障碍服务不豁免**。
 * AOSP `ClipboardService.clipboardAccessAllowed()` 中 `OP_READ_CLIPBOARD` 的豁免名单是：
 * 默认输入法 / 当前焦点窗口 / ContentCapture / 自动填充 / 持有 `READ_CLIPBOARD_IN_BACKGROUND` 者。
 * shell 账号（`com.android.shell`）正持有该权限（`signature|role`，经 `config_systemShell` 授予），
 * 而 Shizuku 的 ADB 模式就是让应用以这个身份调用系统服务。
 *
 * ## 监听器的正确注册方式（最容易踩错的地方）
 * `IOnPrimaryClipChangedListener` 是 AIDL 生成的隐藏接口。系统侧把监听器交给
 * `RemoteCallbackList.register()`，而后者会立刻调用 `callback.asBinder()`，并在派发时
 * 于其持有的对象上做一次 **本地 Java 方法调用** `dispatchPrimaryClipChanged()`。
 *
 * 因此**不能把裸 [Binder] 直接传给系统**——必须先用
 * `IOnPrimaryClipChangedListener$Stub.asInterface(binder)` 包成一个接口实例。
 * 包装后的对象在系统侧是一个 `Proxy`，其方法体就是 `transact(...)`，事件会通过 Binder
 * 回到本进程的 [onTransact]。
 *
 * ## 参数签名逐版本变化
 * `addPrimaryClipChangedListener` 的参数在 Android 14 起从
 * `(listener, String, int)` 变为 `(listener, String, String, int, int)`：
 * - 第 1 个 String 是 `callingPackage`，必须是 `"com.android.shell"`
 * - 第 2 个 String 是 `attributionTag`，传 `"clipboard"` 或 null
 * - `userId` 传 0，`deviceId` 传 0（`DEVICE_ID_DEFAULT`）
 *
 * **`deviceId` 若为无效值，系统会直接 `return` 而不注册**（Android 14 源码行为），
 * 这是"注册成功但回调永不触发"的隐蔽原因。
 */
class ClipboardUserService : Binder() {

    @Volatile
    private var clipboard: Any? = null

    @Volatile
    private var listener: Any? = null

    @Volatile
    private var callback: ClipboardCallbackProxy? = null

    @Volatile
    private var registered = false

    /** 最近一次注册失败的原因，供主进程诊断查询。 */
    @Volatile
    private var registerError: String? = null

    init {
        dropToShellUid()
    }

    // ------------------------------------------------------------ Binder 协议

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            ClipboardProtocol.TRANSACTION_setListener -> {
                data.enforceInterface(ClipboardProtocol.DESCRIPTOR_SERVICE)
                val binder = data.readStrongBinder()
                callback = binder?.let { ClipboardCallbackProxy(it) }
                if (callback == null) unregister() else register()
                reply?.writeNoException()
                return true
            }

            ClipboardProtocol.TRANSACTION_readPrimaryClip -> {
                data.enforceInterface(ClipboardProtocol.DESCRIPTOR_SERVICE)
                val clip = runCatching { invokeGetPrimaryClip() }.getOrNull()
                reply?.writeNoException()
                reply?.writeNullableParcelable(clip, 0)
                return true
            }

            ClipboardProtocol.TRANSACTION_readPrimaryClipDetailed -> {
                data.enforceInterface(ClipboardProtocol.DESCRIPTOR_SERVICE)
                // 必须区分「读取抛异常」与「读到但为空」：
                // 前者是故障，后者是正常状态。混为一谈会让界面误报。
                val outcome = runCatching { invokeGetPrimaryClip() }
                reply?.writeNoException()
                if (outcome.isFailure) {
                    reply?.writeInt(ClipboardProtocol.STATUS_FAILURE)
                } else {
                    val clip = outcome.getOrNull()
                    if (clip == null || clip.itemCount == 0) {
                        reply?.writeInt(ClipboardProtocol.STATUS_EMPTY)
                    } else {
                        reply?.writeInt(ClipboardProtocol.STATUS_OK)
                        reply?.writeTypedObject(clip, 0)
                    }
                }
                return true
            }

            ClipboardProtocol.TRANSACTION_isRegistered -> {
                data.enforceInterface(ClipboardProtocol.DESCRIPTOR_SERVICE)
                reply?.writeNoException()
                reply?.writeInt(if (registered) 1 else 0)
                return true
            }

            ClipboardProtocol.TRANSACTION_getRegisterError -> {
                data.enforceInterface(ClipboardProtocol.DESCRIPTOR_SERVICE)
                reply?.writeNoException()
                reply?.writeString(registerError)
                return true
            }

            ClipboardProtocol.TRANSACTION_destroy -> {
                data.enforceInterface(ClipboardProtocol.DESCRIPTOR_SERVICE)
                unregister()
                callback = null
                reply?.writeNoException()
                System.exit(0)
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    // ------------------------------------------------------------ 监听器注册

    private fun register() {
        if (registered) return

        logEnvironment()

        val icb = obtainClipboard() ?: run {
            registerError = "无法获取 clipboard 系统服务"
            notifyError(registerError!!)
            return
        }

        val listenerObj = buildSystemListener() ?: run {
            registerError = "无法构建剪切板监听器（asInterface 包装失败）"
            notifyError(registerError!!)
            return
        }
        listener = listenerObj
        Log.i(TAG, "监听器对象已构建：${listenerObj.javaClass.name}")

        // 打印设备上真实的全部重载签名，用于确认参数结构。
        val candidates = icb.javaClass.methods
            .filter { it.name == "addPrimaryClipChangedListener" }
            .sortedByDescending { it.parameterTypes.size }

        Log.i(TAG, "发现 ${candidates.size} 个 addPrimaryClipChangedListener 重载：" +
            candidates.joinToString(" | ") { it.describe() })

        if (candidates.isEmpty()) {
            registerError = "未找到 addPrimaryClipChangedListener 方法"
            notifyError(registerError!!)
            return
        }

        val failures = mutableListOf<String>()

        for (method in candidates) {
            val args = buildListenerArgs(method.parameterTypes, listenerObj)
            if (args == null) {
                Log.d(TAG, "跳过签名不匹配的重载：${method.describe()}")
                failures += "${method.describe()} -> 参数无法匹配"
                continue
            }

            Log.i(TAG, "尝试 ${method.describe()}，实参=${args.map { it?.javaClass?.simpleName ?: "null" }}")

            val failure = runCatching { method.invoke(icb, *args) }.exceptionOrNull()
            if (failure == null) {
                registered = true
                Log.i(TAG, "剪切板监听注册调用成功：${method.describe()}")
                verifyRegistration()
                return
            }

            // 反射把目标异常包在 InvocationTargetException 里，真正原因在 targetException。
            val real = (failure as? InvocationTargetException)?.targetException ?: failure
            Log.w(TAG, "调用 ${method.describe()} 失败 -> ${real.javaClass.name}: ${real.message}", real)
            failures += "${method.describe()} -> ${real.javaClass.simpleName}: ${real.message}"
        }

        registerError = failures.joinToString("; ")
        notifyError("注册监听失败：$registerError")
    }

    /**
     * 打印运行环境，用于确认 shell 身份与参数取值。
     *
     * 「读取正常但注册失败」时，这些信息能快速区分是身份问题还是参数问题。
     */
    private fun logEnvironment() {
        Log.i(
            TAG,
            "环境：uid=${android.os.Process.myUid()} " +
                "(期望 2000=shell) pid=${android.os.Process.myPid()} " +
                "packageName=$SHELL_PACKAGE attributionTag=$ATTRIBUTION_TAG userId=$USER_ID deviceId=$DEVICE_ID"
        )
    }

    /**
     * 注册后回读验证。
     *
     * `addPrimaryClipChangedListener` 是 oneway 调用，system_server 若因
     * deviceId 无效而直接 return，客户端完全感知不到——「调用没抛异常」
     * 并不等于「真的注册上了」。这里立刻读一次剪切板，确认链路可用，
     * 并把结果打进日志以便对照。
     */
    private fun verifyRegistration() {
        val clip = runCatching { invokeGetPrimaryClip() }.getOrNull()
        Log.i(
            TAG,
            "回读验证：${if (clip != null) "成功，itemCount=${clip.itemCount}" else "返回 null"}"
        )
    }

    private fun unregister() {
        val icb = clipboard ?: return
        val listenerObj = listener ?: return
        if (!registered) return

        icb.javaClass.methods
            .filter { it.name == "removePrimaryClipChangedListener" }
            .sortedByDescending { it.parameterTypes.size }
            .forEach { method ->
                val args = buildListenerArgs(method.parameterTypes, listenerObj) ?: return@forEach
                runCatching { method.invoke(icb, *args) }
            }
        registered = false
    }

    /**
     * 构建系统能接受的监听器实例。
     *
     * 关键：本类是普通 [Binder]，系统需要的是 `IOnPrimaryClipChangedListener`。
     * 必须经 `IOnPrimaryClipChangedListener$Stub.asInterface(binder)` 包装——
     * 系统侧会调用它的 `asBinder()`，并在派发时调用 `dispatchPrimaryClipChanged()`，
     * 该方法最终通过 Binder 回到下面 [dispatchBinder] 的 [onTransact]。
     */
    @SuppressLint("PrivateApi")
    private fun buildSystemListener(): Any? = runCatching {
        val stubClass = Class.forName("android.content.IOnPrimaryClipChangedListener\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        asInterface.invoke(null, dispatchBinder)
    }.onFailure { Log.e(TAG, "包装监听器失败", it) }.getOrNull()

    /** 承载系统回调的 Binder，收到事件后读一次剪切板并回传主进程。 */
    private val dispatchBinder: IBinder by lazy {
        object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                when (code) {
                    TRANSACTION_dispatchPrimaryClipChanged -> {
                        data.enforceInterface(DESCRIPTOR_LISTENER)
                        handleClipChanged()
                        return true
                    }
                    INTERFACE_TRANSACTION -> {
                        reply?.writeString(DESCRIPTOR_LISTENER)
                        return true
                    }
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
    }

    /**
     * 收到变化事件时立刻读一次剪切板——此刻本进程是 shell 身份，读得到真实内容。
     */
    private fun handleClipChanged() {
        val clip = runCatching { invokeGetPrimaryClip() }.getOrNull()
        if (clip != null && clip.itemCount > 0) {
            runCatching { callback?.onClipChanged(clip) }
        }
    }

    /**
     * 按参数类型填充实参。
     *
     * 判定顺序很重要：先判接口类型（监听器），再判 String，最后是数值类型。
     * 监听器参数位按**接口全名**精确匹配，避免误判其它接口类型。
     *
     * - 监听器参数：传 `asInterface` 包装后的接口实例
     * - 第 1 个 String：`callingPackage`，必须是 `com.android.shell`
     * - 第 2 个 String：`attributionTag`，传 `"clipboard"`
     * - int：`userId` / `deviceId`，都传 0
     *
     * @return 无法匹配时返回 null，调用方应尝试下一个重载
     */
    private fun buildListenerArgs(paramTypes: Array<Class<*>>, listenerObj: Any): Array<Any?>? {
        var listenerAssigned = false
        var stringIndex = 0

        val args = paramTypes.map { type ->
            when {
                // 监听器参数位：优先按全名匹配，退化为"未赋值的接口类型"。
                !listenerAssigned && type.isInterface &&
                    (type.name == DESCRIPTOR_LISTENER || type.isAssignableFrom(listenerObj.javaClass)) -> {
                    listenerAssigned = true
                    listenerObj
                }
                type == String::class.java -> {
                    // 第 1 个是 callingPackage，之后的是 attributionTag。
                    if (stringIndex++ == 0) SHELL_PACKAGE else ATTRIBUTION_TAG
                }
                type == Int::class.javaPrimitiveType || type == Integer::class.java ->
                    if (isUserIdSlot(paramTypes, type)) USER_ID else DEVICE_ID
                type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> 0L
                type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> false
                else -> return null
            }
        }

        // 必须恰好赋值过一次监听器，否则说明签名不符合预期。
        return if (listenerAssigned) args.toTypedArray() else null
    }

    /**
     * 区分 `userId` 与 `deviceId` 两个 int 参数位。
     *
     * 两者都传 0，因此当前实现下无行为差异；保留此判断是为了留下明确的
     * 语义标注，便于后续调整其中一个而不影响另一个。
     */
    private fun isUserIdSlot(paramTypes: Array<Class<*>>, type: Class<*>): Boolean {
        // 第一个 int 位置视为 userId，之后为 deviceId。
        val firstIntIndex = paramTypes.indexOfFirst {
            it == Int::class.javaPrimitiveType || it == Integer::class.java
        }
        return paramTypes.indexOf(type) == firstIntIndex
    }

    private fun Method.describe(): String =
        "$name(${parameterTypes.joinToString { it.simpleName }})"

    // ------------------------------------------------------------ 系统服务调用

    @SuppressLint("PrivateApi")
    private fun obtainClipboard(): Any? {
        clipboard?.let { return it }
        return runCatching {
            val binder: IBinder = SystemServiceHelper.getSystemService("clipboard")
                ?: error("getSystemService(\"clipboard\") 返回 null")

            // 隐藏类无法直接引用，用 IClipboard$Stub.asInterface(binder) 转换。
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, binder).also { clipboard = it }
        }.onFailure { notifyError("获取 clipboard 服务失败：${it.message}") }.getOrNull()
    }

    /**
     * 读取剪切板。
     *
     * `getPrimaryClip` 的签名逐版本变化：
     * - Android 10~13：`getPrimaryClip(String, int)`
     * - Android 14+：  `getPrimaryClip(String, String attributionTag, int userId, int deviceId)`
     */
    private fun invokeGetPrimaryClip(): ClipData? {
        val icb = obtainClipboard() ?: return null
        val method = icb.javaClass.methods.firstOrNull { m ->
            m.name == "getPrimaryClip" &&
                m.parameterTypes.isNotEmpty() &&
                m.parameterTypes.all { it == String::class.java || it == Int::class.javaPrimitiveType }
        } ?: run {
            val available = icb.javaClass.methods
                .filter { it.name.contains("Clipboard") }
                .joinToString { it.describe() }
            Log.w(TAG, "未找到 getPrimaryClip，可用方法：$available")
            return null
        }

        var stringIndex = 0
        val args = method.parameterTypes.map { type ->
            if (type == String::class.java) {
                if (stringIndex++ == 0) SHELL_PACKAGE else null
            } else {
                0
            }
        }.toTypedArray()

        return runCatching { method.invoke(icb, *args) as? ClipData }
            .onFailure { Log.w(TAG, "getPrimaryClip 调用失败", it) }
            .getOrNull()
    }

    private fun notifyError(message: String) {
        runCatching { callback?.onError(message) }
    }

    /**
     * 降到 shell 身份。
     *
     * Shizuku 以 root 模式启动时 UserService 继承 uid 0，而 `clipboardAccessAllowed`
     * 开头的 `mAppOps.checkPackage(uid, callingPackage)` 要求 uid 与
     * `com.android.shell` 包名对应，uid 0 会直接失败。
     * 注意必须先 setgid 再 setuid（setuid 会丢弃 root 权限）。
     * ADB 模式下本来就是 2000，此调用是幂等的。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun dropToShellUid() {
        if (android.os.Process.myUid() != 0) return
        runCatching {
            val os = Class.forName("android.system.Os")
            os.getMethod("setgid", Int::class.javaPrimitiveType).invoke(null, SHELL_UID)
            os.getMethod("setuid", Int::class.javaPrimitiveType).invoke(null, SHELL_UID)
            Log.i(TAG, "已从 root 降权到 shell(2000)")
        }.onFailure { Log.e(TAG, "降权失败", it) }
    }

    private companion object {
        const val TAG = "ClipboardUserService"

        /**
         * 必须是 shell 包名。`clipboardAccessAllowed` 首先调用
         * `mAppOps.checkPackage(uid, callingPackage)`，包名与 uid 不匹配会直接返回 false。
         */
        const val SHELL_PACKAGE = "com.android.shell"

        /** Android 14+ 的 attributionTag 参数值。 */
        const val ATTRIBUTION_TAG = "clipboard"

        /** `userId` 参数。0 表示当前用户。 */
        const val USER_ID = 0

        /**
         * `deviceId` 参数。0 即 `Context.DEVICE_ID_DEFAULT`。
         *
         * Android 14+ 若传入无效值，`ClipboardService.addPrimaryClipChangedListener`
         * 会直接 `return` 而不注册，且因为是 oneway 调用客户端无从感知。
         */
        const val DEVICE_ID = 0

        /** `com.android.shell` 的 uid。 */
        const val SHELL_UID = 2000

        const val DESCRIPTOR_LISTENER = "android.content.IOnPrimaryClipChangedListener"

        /**
         * `IOnPrimaryClipChangedListener` 的事务码。
         * 该接口只声明了 `dispatchPrimaryClipChanged()`，即第一个事务。
         */
        const val TRANSACTION_dispatchPrimaryClipChanged = IBinder.FIRST_CALL_TRANSACTION
    }
}
