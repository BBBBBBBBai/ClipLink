package com.cliplink

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 应用设置。全部走 [SharedPreferences]，量小且无需事务。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 检测到网址后是否直接打开，跳过通知确认。默认关闭，避免误跳转。 */
    var autoOpen: Boolean
        get() = sp.getBoolean(KEY_AUTO_OPEN, false)
        set(value) = sp.edit { putBoolean(KEY_AUTO_OPEN, value) }

    /** 是否只在 Wi-Fi 下监测。默认关闭。 */
    var wifiOnly: Boolean
        get() = sp.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = sp.edit { putBoolean(KEY_WIFI_ONLY, value) }

    /** 是否只在充电时监测。默认关闭。 */
    var chargingOnly: Boolean
        get() = sp.getBoolean(KEY_CHARGING_ONLY, false)
        set(value) = sp.edit { putBoolean(KEY_CHARGING_ONLY, value) }

    /** 历史记录保留条数上限。 */
    var historyLimit: Int
        get() = sp.getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT)
        set(value) = sp.edit { putInt(KEY_HISTORY_LIMIT, value.coerceIn(10, 1000)) }

    /** 总开关：用户是否已启用监测服务。 */
    var monitoringEnabled: Boolean
        get() = sp.getBoolean(KEY_MONITORING_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_MONITORING_ENABLED, value) }

    /** 已捕获链接的累计计数，用于前台通知展示。 */
    var capturedCount: Int
        get() = sp.getInt(KEY_CAPTURED_COUNT, 0)
        set(value) = sp.edit { putInt(KEY_CAPTURED_COUNT, value) }

    /** 上一次通知过的 URL，用于跨进程重启后去重。 */
    var lastUrl: String?
        get() = sp.getString(KEY_LAST_URL, null)
        set(value) = sp.edit { putString(KEY_LAST_URL, value) }

    /** 忽略列表：命中的域名不再提醒。 */
    var ignoredDomains: Set<String>
        get() = sp.getStringSet(KEY_IGNORED_DOMAINS, emptySet()) ?: emptySet()
        set(value) = sp.edit { putStringSet(KEY_IGNORED_DOMAINS, value) }

    /**
     * 用户已通过「测试横幅」确认能收到横幅提醒。
     *
     * ROM 的应用级「横幅」开关没有公开 API 可读取，无法实时检测，
     * 只能靠用户实测确认一次后记住结果。
     */
    var bannerConfirmed: Boolean
        get() = sp.getBoolean(KEY_BANNER_CONFIRMED, false)
        set(value) = sp.edit { putBoolean(KEY_BANNER_CONFIRMED, value) }

    /** 焦点通知（流体云）：开启后链接提醒以流体云实时通知代替普通横幅。 */
    var focusNotification: Boolean
        get() = sp.getBoolean(KEY_FOCUS_NOTIFICATION, false)
        set(value) = sp.edit { putBoolean(KEY_FOCUS_NOTIFICATION, value) }

    /** 流体云胶囊未处理时的停留时长（秒），1–60，默认 10。 */
    var focusTimeoutSeconds: Int
        get() = sp.getInt(KEY_FOCUS_TIMEOUT_SECONDS, DEFAULT_FOCUS_TIMEOUT_SECONDS)
        set(value) = sp.edit { putInt(KEY_FOCUS_TIMEOUT_SECONDS, value.coerceIn(1, 60)) }

    companion object {
        private const val NAME = "cliplink_prefs"
        private const val KEY_AUTO_OPEN = "auto_open"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_CHARGING_ONLY = "charging_only"
        private const val KEY_HISTORY_LIMIT = "history_limit"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_CAPTURED_COUNT = "captured_count"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_IGNORED_DOMAINS = "ignored_domains"
        private const val KEY_BANNER_CONFIRMED = "banner_confirmed"
        private const val KEY_FOCUS_NOTIFICATION = "focus_notification"
        private const val KEY_FOCUS_TIMEOUT_SECONDS = "focus_timeout_seconds"

        const val DEFAULT_HISTORY_LIMIT = 200
        const val DEFAULT_FOCUS_TIMEOUT_SECONDS = 10
    }
}
