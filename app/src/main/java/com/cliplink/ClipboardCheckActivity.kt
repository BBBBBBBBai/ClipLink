package com.cliplink

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * 兜底路径：Shizuku 不可用时，用这个半透明 Activity 抢焦点后读剪切板。
 *
 * ## 为什么需要它
 * Shizuku 未启动（例如重启后未重新启动）时无法后台读取。此 Activity 由常驻通知的
 * 「检测剪切板」按钮启动，是 AOSP 明确允许的路径：系统发送的 PendingIntent 豁免
 * 后台启动 Activity 限制，而 Activity 获得窗口焦点后 `getPrimaryClip()` 才会返回内容。
 *
 * ## 两个关键实现细节
 * 1. **主题必须是半透明**（`Theme.Translucent.NoTitleBar`）。AOSP `validateStartingWindowTheme()`
 *    对 translucent 窗口直接跳过 starting window，因此 Android 12+ 不会闪启动图标。
 * 2. **不能在 `onCreate()` 里读**。焦点是异步获得的，必须等 `onWindowFocusChanged(true)`，
 *    否则会读到 null。同时加超时保护，避免焦点一直不来时窗口卡在屏幕上。
 */
class ClipboardCheckActivity : AppCompatActivity() {

    private var handled = false
    private val handler = Handler(Looper.getMainLooper())

    private val timeout = Runnable {
        if (handled) return@Runnable
        handled = true
        // 焦点没等到，说明系统仍在拒绝，提示用户回到 Shizuku 路径。
        NotificationHelper(this).showShizukuProblem(getString(R.string.check_focus_timeout))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不 setContentView：本 Activity 无界面，只借它的焦点。
        handler.postDelayed(timeout, FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled) {
            handled = true
            handler.removeCallbacks(timeout)
            readClipboard()
        }
    }

    private fun readClipboard() {
        val notifications = NotificationHelper(this)
        val text = readClipboardText()

        when {
            text.isNullOrBlank() -> {
                notifications.showNoLink()
            }
            else -> {
                val url = UrlExtractor.extractFirst(text)
                if (url == null) {
                    notifications.showNoLink()
                } else {
                    // 复用与 Shizuku 路径相同的落库与通知逻辑。
                    LinkRepository(this).add(url, source = "fallback")
                    notifications.showLinkFound(url)
                }
            }
        }

        finish()
    }

    private fun readClipboardText(): String? = runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(this).toString()
    }.getOrNull()

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        super.onDestroy()
    }

    private companion object {
        /** 焦点等待上限。超过即认为系统拒绝了读取，避免窗口停留。 */
        const val FOCUS_TIMEOUT_MS = 1500L
    }
}
