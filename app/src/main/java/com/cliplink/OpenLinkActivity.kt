package com.cliplink

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 处理结果通知上的「打开」动作。
 *
 * 用 Activity 而不是 BroadcastReceiver：Android 12+ 禁止通知蹦床
 * （不能在 Receiver 里 `startActivity()`），且从 Activity 启动浏览器
 * 不需要额外的后台启动权限。
 *
 * 本 Activity 不设布局，跳转后立即结束，视觉上只是一闪。
 */
class OpenLinkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.data?.toString()
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        openInBrowser(url)
        finish()
    }

    /**
     * 用系统默认浏览器打开。不指定 `setPackage`，交给系统解析默认浏览器。
     */
    private fun openInBrowser(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null) {
            toast(getString(R.string.open_failed_invalid))
            return
        }

        val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 没有可处理的应用时 startActivity 会抛异常，必须提前判空。
        if (viewIntent.resolveActivity(packageManager) == null) {
            toast(getString(R.string.open_failed_no_browser))
            return
        }

        try {
            startActivity(viewIntent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.open_failed_no_browser))
        }

        NotificationHelper(this).cancelResult()
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
