package com.cliplink

import android.app.Application
import android.util.Log

/**
 * 应用入口。
 *
 * 这里只做最低限度的初始化：真正的监听逻辑在 [ClipboardMonitorService] 里，
 * 不放任何耗时或需要权限的操作，避免拖慢冷启动。
 */
class ClipLinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 触碰一次 Prefs，确保默认值在首次启动时即完成写入。
        Prefs(this)
        Log.i(TAG, "ClipLink 启动，minSdk=${android.os.Build.VERSION.SDK_INT}")
    }

    private companion object {
        const val TAG = "ClipLinkApp"
    }
}
