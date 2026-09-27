package com.cliplink.shizuku

import android.content.ClipData
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * UserService 与主进程之间的 Binder 协议。
 *
 * ## 为什么手写而不是用 AIDL
 * AIDL 编译器（`compileDebugAidl`）在 Windows + 含中文的项目路径下会抛
 * `MalformedInputException`，且无法通过 `-Dfile.encoding` 规避。
 * 这里改用 Binder 的 `transact`/`onTransact` 手写协议，功能等价而去掉了该编译环节。
 *
 * 协议约定（事务码从 [TRANSACTION_setListener] 开始，避免与 Binder 内置码冲突）：
 * - [TRANSACTION_setListener]：`writeStrongBinder(listener)`，注册或注销监听
 * - [TRANSACTION_readPrimaryClip]：无参，回包为可空的 ClipData（无法区分空与失败，仅供读取内容使用）
 * - [TRANSACTION_readPrimaryClipDetailed]：无参，回包为状态码 + 可空 ClipData（用于状态检测）
 * - [TRANSACTION_isRegistered]：无参，回包为布尔
 * - [TRANSACTION_destroy]：无参，结束 UserService 进程
 *
 * 反向回调（UserService -> 主进程）由 [IClipboardCallback] 承担。
 */
internal object ClipboardProtocol {

    const val DESCRIPTOR_SERVICE = "com.cliplink.shizuku.IClipboardService"
    const val DESCRIPTOR_CALLBACK = "com.cliplink.shizuku.IClipboardCallback"

    /** 从 1 开始，避开 Binder 内置事务码（INTERFACE_TRANSACTION 等为负数或 0）。 */
    const val TRANSACTION_setListener = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_readPrimaryClip = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_isRegistered = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_destroy = IBinder.FIRST_CALL_TRANSACTION + 3

    /** 查询注册失败原因，用于主界面诊断展示。 */
    const val TRANSACTION_getRegisterError = IBinder.FIRST_CALL_TRANSACTION + 4

    /** 带状态区分的读取，用于诊断。 */
    const val TRANSACTION_readPrimaryClipDetailed = IBinder.FIRST_CALL_TRANSACTION + 5

    const val TRANSACTION_onClipChanged = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_onError = IBinder.FIRST_CALL_TRANSACTION + 1

    /** 读取成功，且内容非空。 */
    const val STATUS_OK = 1

    /** 读取成功，但剪切板为空——这是**正常状态**，不是故障。 */
    const val STATUS_EMPTY = 2

    /** 读取失败：权限失效或服务异常。 */
    const val STATUS_FAILURE = 3
}

/**
 * 剪切板读取的结果。
 *
 * 必须区分 [Empty] 与 [Failure]：前者是正常的"剪切板没内容"，
 * 后者才是真正的异常。用单个 `null` 表示两者会导致状态检测误报。
 */
sealed interface ClipboardReadResult {
    /** 读取成功，[clip] 可能为 null（剪切板为空）。 */
    data class Success(val clip: ClipData?) : ClipboardReadResult

    /** 读取成功但剪切板为空。 */
    data object Empty : ClipboardReadResult

    /** 读取失败。 */
    data object Failure : ClipboardReadResult
}

/**
 * 主进程侧对 UserService 的调用封装。
 *
 * 持有 Shizuku 返回的原始 [IBinder]，所有方法通过 [IBinder.transact] 发起调用，
 * 运行在 shell 身份下。
 */
class ClipboardServiceProxy(private val binder: IBinder) {

    /** 注册监听。传 null 表示注销。 */
    fun setListener(callback: IClipboardCallback?) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ClipboardProtocol.DESCRIPTOR_SERVICE)
            data.writeStrongBinder(callback?.asBinder())
            binder.transact(ClipboardProtocol.TRANSACTION_setListener, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 主动读一次剪切板，失败返回 null。 */
    fun readPrimaryClip(): ClipData? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ClipboardProtocol.DESCRIPTOR_SERVICE)
            binder.transact(ClipboardProtocol.TRANSACTION_readPrimaryClip, data, reply, 0)
            reply.readException()
            if (reply.readInt() != 0) {
                reply.readTypedObject(ClipData.CREATOR)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * 读取剪切板，并区分三种结果。
     *
     * 直接用 [readPrimaryClip] 的返回值判断"是否正常"是错误的：
     * `null` 既可能是**读取失败**（权限失效、服务异常），也可能是
     * **剪切板本来就是空的**（正常情况）。两者混淆会把正常状态误报为故障。
     *
     * @return [ClipboardReadResult]
     */
    fun readPrimaryClipDetailed(): ClipboardReadResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ClipboardProtocol.DESCRIPTOR_SERVICE)
            binder.transact(ClipboardProtocol.TRANSACTION_readPrimaryClipDetailed, data, reply, 0)
            reply.readException()
            when (reply.readInt()) {
                ClipboardProtocol.STATUS_OK ->
                    ClipboardReadResult.Success(reply.readTypedObject(ClipData.CREATOR))
                ClipboardProtocol.STATUS_EMPTY -> ClipboardReadResult.Empty
                else -> ClipboardReadResult.Failure
            }
        } catch (e: Exception) {
            // Binder 调用本身失败：服务已死或权限被撤销。
            ClipboardReadResult.Failure
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 监听是否已成功注册。 */
    fun isRegistered(): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ClipboardProtocol.DESCRIPTOR_SERVICE)
            binder.transact(ClipboardProtocol.TRANSACTION_isRegistered, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: Exception) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 让 UserService 退出。 */
    fun destroy() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ClipboardProtocol.DESCRIPTOR_SERVICE)
            binder.transact(ClipboardProtocol.TRANSACTION_destroy, data, reply, 0)
            reply.readException()
        } catch (e: Exception) {
            // 进程可能已退出，忽略。
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 查询注册失败原因；成功注册或无错误时返回 null。 */
    fun getRegisterError(): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ClipboardProtocol.DESCRIPTOR_SERVICE)
            binder.transact(ClipboardProtocol.TRANSACTION_getRegisterError, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: Exception) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

/**
 * 主进程实现的回调接口，由 UserService 通过 Binder 反向调用。
 */
interface IClipboardCallback {
    fun onClipChanged(clip: ClipData?)
    fun onError(message: String?)
    fun asBinder(): IBinder
}

/**
 * [IClipboardCallback] 的 Binder 实现。
 *
 * 使用 `FLAG_ONEWAY` 语义（`transact` 的 flag 为 1）避免 UserService 阻塞在回调上。
 */
class ClipboardCallback(private val handler: (ClipData?, String?) -> Unit) :
    Binder(), IClipboardCallback {

    override fun onClipChanged(clip: ClipData?) = handler(clip, null)

    override fun onError(message: String?) = handler(null, message)

    override fun asBinder(): IBinder = this

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            ClipboardProtocol.TRANSACTION_onClipChanged -> {
                data.enforceInterface(ClipboardProtocol.DESCRIPTOR_CALLBACK)
                // 内容可能为 null，Parcel 用长度前缀表示可空 Parcelable。
                val clip = data.readTypedObject(ClipData.CREATOR)
                handler(clip, null)
                reply?.writeNoException()
                return true
            }
            ClipboardProtocol.TRANSACTION_onError -> {
                data.enforceInterface(ClipboardProtocol.DESCRIPTOR_CALLBACK)
                val message = data.readString()
                handler(null, message)
                reply?.writeNoException()
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }
}

/**
 * 把 Binder 包装成 [IClipboardCallback]，供 UserService 反向调用主进程。
 *
 * UserService 侧只需持有主进程传入的 [IBinder]。
 */
class ClipboardCallbackProxy(private val binder: IBinder) {

    fun onClipChanged(clip: ClipData?) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(ClipboardProtocol.DESCRIPTOR_CALLBACK)
            data.writeTypedObject(clip, 0)
            // flag = 1 即 FLAG_ONEWAY，不等回包，避免阻塞监听线程。
            binder.transact(ClipboardProtocol.TRANSACTION_onClipChanged, data, null, 1)
        } catch (e: Exception) {
            // 主进程可能已退出，忽略。
        } finally {
            data.recycle()
        }
    }

    fun onError(message: String?) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(ClipboardProtocol.DESCRIPTOR_CALLBACK)
            data.writeString(message)
            binder.transact(ClipboardProtocol.TRANSACTION_onError, data, null, 1)
        } catch (e: Exception) {
            // 同上。
        } finally {
            data.recycle()
        }
    }
}

/** Parcel 读写可空 Parcelable 的小工具，避免各处重复写长度前缀。 */
internal fun Parcel.writeNullableParcelable(value: Parcelable?, flags: Int) {
    if (value == null) {
        writeInt(0)
    } else {
        writeInt(1)
        writeTypedObject(value, flags)
    }
}
