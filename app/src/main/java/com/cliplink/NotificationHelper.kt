package com.cliplink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 通知构建与状态切换。
 *
 * 两条通知：
 * - **常驻通知**（低优先级）：展示监测服务是否在跑，带「检测剪切板」按钮
 * - **结果通知**（默认优先级）：检测到网址后出现，带「打开 / 忽略」按钮
 */
class NotificationHelper(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    // ------------------------------------------------------------ 渠道

    /**
     * 创建通知渠道。
     *
     * ## 横幅（浮动通知）的关键约束
     * 只有 `IMPORTANCE_HIGH` 的渠道才会弹出横幅。`IMPORTANCE_DEFAULT` 只进通知栏，
     * 不浮现——这正是之前检测到链接时看不到屏幕上方提示的原因。
     *
     * **渠道的重要性创建后不可由应用修改**：对已存在的渠道再次调用
     * `createNotificationChannel` 只会更新名称与描述，重要性变更会被系统忽略。
     * 因此若重要性需要调整，必须使用**新的渠道 id**（见 [CHANNEL_RESULT] 的版本后缀），
     * 否则老用户会一直沿用旧设置。
     *
     * 另外，用户随时可以在系统设置里把渠道重要性调低，应用无法覆盖这一选择——
     * 界面上的"开启横幅"入口（[openChannelSettings]）是唯一的引导手段。
     */
    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        // 常驻通知用 LOW：不响铃、不弹横幅，避免长期打扰。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_status_desc)
                setShowBadge(false)
            }
        )

        // 结果通知用 IMPORTANCE_HIGH。
        //
        // 注意：不要用 IMPORTANCE_MAX。AOSP 中它的注释就是 "Unused."，
        // 且 SystemUI 的横幅判定只有 `importance < IMPORTANCE_HIGH` 这一个比较，
        // 4 与 5 被完全同等对待，设成 MAX 没有任何额外效果。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.channel_result),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_result_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                setShowBadge(true)
            }
        )

        // 焦点通知（流体云）渠道：LOW 重要性即可——实时更新的胶囊/卡片本身就是
        // 提醒面，静默以免与横幅双重打扰；Live Updates 规范只要求渠道重要性非 MIN。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FOCUS,
                context.getString(R.string.focus_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.focus_channel_desc)
                setShowBadge(false)
            }
        )
    }

    /**
     * 应用级通知总开关是否打开。
     *
     * 比运行时权限判断更贴近真实状态：Android 8–12 没有运行时通知权限，
     * 但用户仍可在系统设置里整体关闭本应用的通知；部分 ROM 上
     * 总开关与运行时权限的报告也可能不一致。任务清单用它做「允许通知」的判定。
     */
    fun areNotificationsEnabled(): Boolean = manager.areNotificationsEnabled()

    /**
     * 判断结果通知渠道是否具备弹横幅的级别。
     *
     * 以 HIGH 为门槛而非 MAX：用户手动把渠道设为 HIGH 同样能得到横幅，
     * 不应因为低于 MAX 就判定为"未开启"。
     */
    /**
     * 结果渠道的重要性是否达到可弹横幅的级别。
     *
     * ## 注意这只是必要条件，不是充分条件
     * 重要性 >= HIGH 只说明**渠道层面**允许横幅。部分 ROM（ColorOS / MIUI 等）
     * 还有独立的**应用级「横幅」开关**，且默认关闭——那种情况下重要性再高
     * 也不会弹横幅，而该开关应用无法通过代码开启。
     *
     * 因此本方法不能用来断言"横幅一定可用"，只用于判断渠道配置是否正确。
     * 方法名刻意写成"channel"而非"headsUp"，避免误导。
     */
    fun isChannelHeadsUpCapable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        // 获取不到服务时返回 true 而非 false：无法判断不等于"未开启"，
        // 避免把系统异常误报成用户配置问题。
        val nm = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = nm.getNotificationChannel(CHANNEL_RESULT) ?: return true
        return channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    /**
     * 读取结果渠道的真实状态，用于诊断横幅为何不出现。
     *
     * 关键点：**渠道重要性一旦创建就无法由应用修改**，对已存在的渠道调用
     * [createNotificationChannel] 会静默忽略重要性变更。因此必须读出**实际值**
     * 才能判断问题出在哪一层：
     * - `importance < HIGH` → 渠道级别不够，需换渠道 id 或引导用户手动调高
     * - `importance >= HIGH` 但仍无横幅 → 是系统的「横幅」开关被关了，
     *   或处于免打扰/静音模式，应用侧无法强制
     */
    fun describeChannelState(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "Android 8 以下无渠道概念"
        }
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return "无法获取 NotificationManager"
        val channel = nm.getNotificationChannel(CHANNEL_RESULT)
            ?: return "渠道 $CHANNEL_RESULT 不存在"

        val importanceLabel = when (channel.importance) {
            NotificationManager.IMPORTANCE_MAX -> "MAX（可横幅）"
            NotificationManager.IMPORTANCE_HIGH -> "HIGH（可横幅）"
            NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT（不横幅）"
            NotificationManager.IMPORTANCE_LOW -> "LOW（不横幅）"
            NotificationManager.IMPORTANCE_MIN -> "MIN（不横幅）"
            NotificationManager.IMPORTANCE_NONE -> "NONE（已屏蔽）"
            else -> "未知(${channel.importance})"
        }

        return buildString {
            appendLine("渠道: ${channel.id}")
            appendLine("重要性: $importanceLabel")
            appendLine("响铃: ${if (channel.shouldVibrate()) "震动" else "静音"}")
            append("通知总开关: ${if (nm.areNotificationsEnabled()) "已开启" else "已关闭"}")
        }
    }

    // ------------------------------------------------------------ 常驻通知

    /**
     * 更新常驻通知。
     *
     * @param ready   Shizuku 监听是否就绪，决定文案与按钮
     * @param reason  未就绪时的原因，展示在副标题
     */
    fun showStatus(ready: Boolean, reason: String? = null, captured: Int = 0) {
        notifySafely(ID_STATUS, buildStatus(ready, reason, captured))
    }

    /**
     * 只构建常驻通知，不发布。
     *
     * 前台服务的 `startForeground()` 必须直接拿到 [Notification] 实例，
     * 因此这里与 [showStatus] 分开。
     */
    fun buildStatus(ready: Boolean, reason: String? = null, captured: Int = 0): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            REQ_OPEN_APP,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingFlags()
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(
                if (ready) context.getString(R.string.status_running)
                else context.getString(R.string.status_paused)
            )
            .setContentText(
                if (ready) context.getString(R.string.status_running_desc, captured)
                else reason ?: context.getString(R.string.status_paused_desc)
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (ready) {
            builder.addAction(
                0,
                context.getString(R.string.action_check_clipboard),
                checkClipboardIntent()
            )
        } else {
            builder.addAction(
                0,
                context.getString(R.string.action_fix_shizuku),
                contentIntent
            )
        }

        return builder.build()
    }

    // ------------------------------------------------------------ 结果通知

    /**
     * 展示"检测到网址"的结果通知。
     *
     * 焦点通知开关开启且系统支持（Android 16+ 且用户允许了实时更新）时，
     * 走流体云实时通知（[showLinkFocus]）；否则走普通横幅通知。
     *
     * @param url 提取到的网址
     */
    fun showLinkFound(url: String) {
        if (isFocusActive()) {
            showLinkFocus(url)
            return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(context.getString(R.string.link_found_title))
            .setContentText(url)
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setContentIntent(mainEntryPendingIntent())
            .setAutoCancel(true)
            // 渠道重要性决定横幅；Android 8 以下及部分 ROM 的兼容逻辑还会读
            // priority 并取较保守值，因此与渠道的 IMPORTANCE_HIGH 对齐用 MAX。
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            // 每次都是新事件，时间戳必须新鲜：SystemUI 会抑制 when 超过 24 小时的
            // 通知的横幅（shouldSuppressHeadsUpWhenAwakeForOldWhen）。
            .setWhen(System.currentTimeMillis())
            .setOnlyAlertOnce(false)
            .addAction(0, context.getString(R.string.action_open), openLinkPendingIntent(url))
            .addAction(0, context.getString(R.string.action_ignore), dismissIntent())
            .build()

        notifySafely(ID_RESULT, notification)
    }

    /**
     * 以「实时更新」（Live Updates）通知展示链接，ColorOS 16+ 将其渲染为流体云。
     *
     * 按 Android 16 Live Updates 规范构造：`setOngoing` + `setRequestPromotedOngoing`
     * 请求提升、有 contentTitle、无自定义 RemoteViews、渠道重要性非 MIN（LOW）。
     * 停留时长由用户设置（超时自动消失）；「打开」动作即跳转按钮，
     * 与普通通知共用 OpenLinkActivity 与「忽略」清理路径（同一个通知 id）。
     */
    private fun showLinkFocus(url: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_FOCUS)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(context.getString(R.string.link_found_title))
            .setContentText(url)
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setContentIntent(mainEntryPendingIntent())
            // 实时更新必须常驻（FLAG_ONGOING_EVENT）；消失靠超时与「忽略」动作。
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            // 状态栏胶囊上的文字。URL 长度不可控放不进胶囊，用固定短语。
            .setShortCriticalText(context.getString(R.string.focus_chip_text))
            .addAction(0, context.getString(R.string.action_open), openLinkPendingIntent(url))
            .addAction(0, context.getString(R.string.action_ignore), dismissIntent())
            .setTimeoutAfter(Prefs(context).focusTimeoutSeconds * 1000L)
            .build()

        notifySafely(ID_RESULT, notification)
    }

    /**
     * 焦点通知（流体云）当前是否生效：开关打开、系统为 Android 16+，
     * 且用户在系统设置里允许了本应用的「实时更新」。
     */
    private fun isFocusActive(): Boolean {
        if (!Prefs(context).focusNotification) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.canPostPromotedNotifications()
    }

    private fun openLinkPendingIntent(url: String): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_OPEN_URL,
        Intent(context, OpenLinkActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        pendingFlags()
    )

    private fun mainEntryPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_OPEN_APP,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        pendingFlags()
    )

    /**
     * 跳转到结果渠道的通知设置页。
     *
     * 渠道重要性不足（弹不了横幅）时用它——渠道页是调整渠道重要性的唯一入口。
     * 注意 ROM 的应用级「横幅」开关**不在这里**：它位于应用通知页，
     * 且该开关未开启时，渠道页上甚至不会出现横幅选项
     * （横幅实测失败请用 [openAppNotificationSettings]）。
     *
     * 优先跳渠道设置页，失败则退回应用通知设置页。
     *
     * @return 是否成功跳转
     */
    fun openChannelSettingsAndFallback(): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_RESULT)
                )
            }
            add(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
            )
        }
        return startFirst(candidates)
    }

    /**
     * 跳转到本应用的「应用通知」设置页。
     *
     * ## 为什么横幅实测失败要来这里而不是渠道页
     * ROM 的应用级「横幅 / 悬浮通知」开关位于应用通知页；它关闭时，
     * 渠道设置页上根本不会出现横幅选项，跳过去用户什么也找不到。
     * 应用通知页同时列出了各渠道入口，任何机型都能从这里到达所需开关。
     *
     * @return 是否成功跳转
     */
    fun openAppNotificationSettings(): Boolean = startFirst(
        listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            // 个别老系统没有应用通知页，退到应用详情页，通知入口在那里也能找到
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        )
    )

    /** 逐个尝试候选 intent，启动第一个可用的。 */
    private fun startFirst(candidates: List<Intent>): Boolean {
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /**
     * 发一条测试通知，用于验证横幅是否真的能弹出。
     *
     * ROM 的应用级「横幅」开关没有公开 API 可读取，任务清单只能靠
     * 用户实测确认：发出通知后由界面弹窗询问用户是否看到了横幅。
     * 走结果渠道（IMPORTANCE_HIGH），与真实链接提醒完全同路径。
     */
    fun showBannerTest() {
        val text = context.getString(R.string.banner_test_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(context.getString(R.string.banner_test_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            // 短暂自动消失，不留垃圾通知；时间戳取当下，避免横幅被抑制。
            .setTimeoutAfter(6000)
            .setWhen(System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notifySafely(ID_RESULT, notification)
    }

    /** 提示未检测到网址。短暂展示后自动消失。 */
    fun showNoLink() {
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(context.getString(R.string.no_link_title))
            .setContentText(context.getString(R.string.no_link_desc))
            .setAutoCancel(true)
            .setTimeoutAfter(4000)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notifySafely(ID_RESULT, notification)
    }

    /** 提示 Shizuku 相关异常，例如服务被杀。 */
    fun showShizukuProblem(reason: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(context.getString(R.string.shizuku_problem_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQ_OPEN_APP,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    pendingFlags()
                )
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notifySafely(ID_RESULT, notification)
    }

    fun cancelResult() = manager.cancel(ID_RESULT)

    fun cancelStatus() = manager.cancel(ID_STATUS)

    // ------------------------------------------------------------ 内部工具

    /** 兜底路径：点按钮后由半透明 Activity 抢焦点读取。 */
    private fun checkClipboardIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_CHECK_CLIPBOARD,
        Intent(context, ClipboardCheckActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        pendingFlags()
    )

    private fun dismissIntent(): PendingIntent {
        val intent = Intent(context, ClipboardMonitorService::class.java)
            .setAction(ClipboardMonitorService.ACTION_DISMISS)
        return PendingIntent.getService(context, REQ_DISMISS, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /**
     * 通知权限在 Android 13+ 可能未授予，直接 notify 会抛 SecurityException。
     */
    private fun notifySafely(id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }
    }

    companion object {
        /**
         * 常驻通知的 id 必须与 [ClipboardMonitorService.startForeground] 使用的一致，
         * 否则通知栏会出现两条。
         */
        const val ID_STATUS = 1001
        const val ID_RESULT = 1002

        private const val CHANNEL_STATUS = "cliplink_status"
        private const val CHANNEL_FOCUS = "cliplink_focus"

        /**
         * 结果通知渠道 id。
         *
         * ## 为什么带版本后缀
         * **渠道重要性一经创建就无法由应用修改**：对已存在的渠道再次调用
         * `createNotificationChannel` 只会更新名称与描述，重要性变更被系统忽略。
         * 因此每次调整重要性都必须启用**新的渠道 id**，否则老用户会一直沿用旧值。
         *
         * - `cliplink_result`    → IMPORTANCE_DEFAULT（不弹横幅）
         * - `cliplink_result_v2` → IMPORTANCE_HIGH
         * - `cliplink_result_v3` → IMPORTANCE_MAX（当前，为兼容国产 ROM 的横幅策略）
         */
        private const val CHANNEL_RESULT = "cliplink_result_v3"

        private const val REQ_OPEN_APP = 2001
        private const val REQ_OPEN_URL = 2002
        private const val REQ_CHECK_CLIPBOARD = 2003
        private const val REQ_DISMISS = 2004
    }
}
