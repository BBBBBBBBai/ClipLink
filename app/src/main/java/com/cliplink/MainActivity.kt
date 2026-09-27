package com.cliplink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cliplink.databinding.ActivityMainBinding
import com.cliplink.shizuku.ClipboardReadResult
import com.cliplink.shizuku.ShizukuHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 主界面：展示运行状态、引导修复权限、提供测试与入口。
 *
 * 这里也是唯一主动请求权限的地方——前台服务里不能弹权限框。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var shizuku: ShizukuHelper
    private lateinit var notifications: NotificationHelper

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        shizuku = ShizukuHelper(this)
        notifications = NotificationHelper(this)

        // 全面屏适配：给根布局加系统栏内边距，避免内容被状态栏/导航栏遮挡。
        // 布局内层已有 24dp/32dp 的视觉留白，这里不再额外叠加，避免留白过大。
        InsetsHelper.applyToRoot(
            activity = this,
            root = binding.root
        )

        binding.primaryAction.setOnClickListener { onPrimaryAction() }
        binding.secondaryAction.setOnClickListener { openShizukuApp() }
        binding.testButton.setOnClickListener { testClipboard() }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.permShizukuButton.setOnClickListener { onShizukuTaskAction() }
        binding.permNotifButton.setOnClickListener { onNotificationTaskAction() }
        binding.permBatteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.refreshDiagnosticButton.setOnClickListener { recheckDiagnostics() }
    }

    override fun onResume() {
        super.onResume()
        // 首次进入与从后台返回不播动画：此时界面本该"就位"，
        // 播过渡会让用户看到一次无意义的入场动效。
        refresh(animated = false)
    }

    // ------------------------------------------------------------ 状态刷新

    /**
     * 刷新界面。
     *
     * @param animated 是否用过渡动画承载布局变化。
     *        用户主动操作（点开始/停止）时为 true——此时卡片高度会变化，
     *        动画能避免生硬的跳变；自动刷新时为 false。
     */
    private fun refresh(animated: Boolean = false) {
        val state = resolveState()

        if (animated) {
            LayoutAnimator.animateIfEnabled(binding.contentContainer) {
                applyAll(state)
            }
        } else {
            applyAll(state)
        }
    }

    /** 一次性应用全部状态，动画包裹时应作为一个整体执行。 */
    private fun applyAll(state: DisplayState) {
        applyState(state)
        applyPermissionTasks()
        updateCapturedCount()
        updateDiagnostics(state)
    }

    /**
     * 应用权限任务清单。
     *
     * 三项任务（Shizuku / 通知横幅 / 后台行为）全部完成时**整卡隐藏**——
     * 没有待处理的权限时这张卡片纯属占位，只会增加视觉噪声。
     * 任何一项失效（ROM 重置电池优化、渠道重要性被调低、Shizuku 重启后掉权限），
     * 下次刷新时卡片会重新出现。
     *
     * 状态全部实时计算、不做持久化：从系统设置或 Shizuku 应用返回时，
     * onResume 会重新评估一遍。
     */
    private fun applyPermissionTasks() {
        val shizukuState = shizuku.currentState()
        // 通知判定用应用级总开关：它同时覆盖 Android 13+ 的运行时权限
        // 和 8–12 的设置页开关，比单看运行时权限更贴近真实状态。
        val notifEnabled = notifications.areNotificationsEnabled()
        val channelOk = notifEnabled && notifications.isChannelHeadsUpCapable()
        val notifDone = channelOk && prefs.bannerConfirmed
        val batteryOk = isIgnoringBatteryOptimizations()

        val allDone = shizukuState == ShizukuHelper.State.READY && notifDone && batteryOk
        binding.permissionCard.visibility = if (allDone) View.GONE else View.VISIBLE
        if (allDone) return

        applyShizukuTask(shizukuState)
        applyNotificationTask(notifEnabled, channelOk)
        applyBatteryTask(batteryOk)
    }

    /** 统一渲染一行任务：圆点颜色、状态文本与按钮可见性。 */
    private fun bindTaskRow(
        dot: View,
        status: TextView,
        button: MaterialButton,
        done: Boolean,
        statusRes: Int,
        actionRes: Int
    ) {
        dot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (done) R.color.state_ok else R.color.state_warn)
        )
        status.setText(statusRes)
        status.setTextColor(
            ContextCompat.getColor(
                this, if (done) R.color.state_ok else R.color.on_surface_variant
            )
        )
        button.setText(actionRes)
        button.visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun applyShizukuTask(state: ShizukuHelper.State) {
        bindTaskRow(
            dot = binding.permShizukuDot,
            status = binding.permShizukuStatus,
            button = binding.permShizukuButton,
            done = state == ShizukuHelper.State.READY,
            statusRes = when (state) {
                ShizukuHelper.State.NOT_INSTALLED -> R.string.state_not_installed
                ShizukuHelper.State.NOT_RUNNING -> R.string.state_not_running
                ShizukuHelper.State.NO_PERMISSION -> R.string.perm_shizuku_no_permission
                ShizukuHelper.State.READY -> R.string.perm_task_done
            },
            actionRes = when (state) {
                ShizukuHelper.State.NOT_INSTALLED -> R.string.action_install_shizuku
                ShizukuHelper.State.NOT_RUNNING -> R.string.action_open_shizuku
                else -> R.string.action_grant
            }
        )
    }

    /**
     * 通知横幅任务的三个待办状态：
     * 1. 通知未允许 → 授予 / 去设置
     * 2. 渠道重要性不足（弹不了横幅）→ 去设置
     * 3. 渠道已达标 → 「测试横幅」实测确认。
     *
     * 第 3 步不可省：ROM 的应用级「横幅」开关（MIUI/ColorOS 等）默认关闭
     * 且没有公开 API 可读取，渠道达标只说明应用侧配置正确，
     * 只有用户亲眼看到横幅才能确认整条链路可用。
     */
    private fun applyNotificationTask(notifEnabled: Boolean, channelOk: Boolean) {
        when {
            !notifEnabled -> bindTaskRow(
                dot = binding.permNotifDot,
                status = binding.permNotifStatus,
                button = binding.permNotifButton,
                done = false,
                statusRes = R.string.perm_notif_denied,
                // Android 13+ 走运行时授权框；更早版本没有运行时权限，
                // 只能跳系统通知设置页打开总开关。
                actionRes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    R.string.action_grant
                } else {
                    R.string.action_go_settings
                }
            )

            !channelOk -> bindTaskRow(
                dot = binding.permNotifDot,
                status = binding.permNotifStatus,
                button = binding.permNotifButton,
                done = false,
                statusRes = R.string.perm_notif_no_banner,
                actionRes = R.string.action_go_settings
            )

            else -> bindTaskRow(
                dot = binding.permNotifDot,
                status = binding.permNotifStatus,
                button = binding.permNotifButton,
                done = prefs.bannerConfirmed,
                statusRes = if (prefs.bannerConfirmed) {
                    R.string.perm_task_done
                } else {
                    R.string.perm_notif_need_confirm
                },
                actionRes = R.string.action_test_banner
            )
        }
    }

    private fun applyBatteryTask(done: Boolean) {
        bindTaskRow(
            dot = binding.permBatteryDot,
            status = binding.permBatteryStatus,
            button = binding.permBatteryButton,
            done = done,
            statusRes = if (done) R.string.perm_task_done else R.string.perm_battery_pending,
            actionRes = R.string.action_go_settings
        )
    }

    /**
     * 展示诊断信息。
     *
     * 「复制了但没提醒」通常出在两处：监听没注册成功，或 Shizuku 权限/服务不可用。
     *
     * ## 为什么不显示加载态
     * 切换深色模式、旋转屏幕等都会重建 Activity，`onResume` 会重新走到这里。
     * 若每次都先显示"正在检测…"再查询，用户每次都会看到一次闪烁。
     * 因此把上次结果缓存在进程级（[DiagnosticsCache]）：
     * 有缓存就直接渲染，再在后台静默刷新；只有首次查询才可能出现空档。
     */
    private fun updateDiagnostics(state: DisplayState) {
        if (state != DisplayState.Ready) {
            binding.diagnosticRow.visibility = View.GONE
            return
        }

        binding.diagnosticRow.visibility = View.VISIBLE

        // 已有结果：立即渲染，不做任何加载提示。
        DiagnosticsCache.text?.let { binding.diagnosticText.text = it }

        refreshDiagnostics()
    }

    /**
     * 后台查询诊断信息并更新界面。
     *
     * ## 为什么状态检测会"不稳定"
     * 诊断查询依赖与 UserService 的 Binder 连接，而绑定是**异步**的。
     * 若查询恰好落在绑定完成之前，就会读到"未就绪"，界面显示异常状态；
     * 稍后再查又是正常的。这不是功能故障，而是**检测时机**问题。
     *
     * 对策有三层：
     * 1. 用 [ShizukuHelper.bindReadOnly] 的回调驱动查询，而非猜测耗时；
     * 2. 绑定成功但查询仍失败时自动重试若干次（[DIAG_RETRY_DELAYS_MS]）；
     * 3. 界面上提供「重新检测」按钮，让用户可手动重试。
     *
     * @param attempt 当前重试序号，首次调用传 0
     */
    private fun refreshDiagnostics(attempt: Int = 0) {
        val started = shizuku.bindReadOnly {
            // 回调可能在主线程（已绑定）或 Binder 线程（连接建立）触发，
            // 统一丢到后台线程执行查询。
            diagnosticsExecutor.execute {
                val ready = shizuku.isReady()
                val text = queryDiagnostics()

                binding.root.post {
                    // 界面可能已切走或状态已变，回到主线程后再确认。
                    if (isFinishing || resolveState() != DisplayState.Ready) return@post

                    // 连接尚未就绪且还有重试机会：稍后重试，不把中间态写进界面。
                    // 这消除了"闪一下异常再变正常"的抖动。
                    if (!ready && attempt < DIAG_RETRY_DELAYS_MS.size) {
                        binding.diagnosticText.postDelayed(
                            { refreshDiagnostics(attempt + 1) },
                            DIAG_RETRY_DELAYS_MS[attempt]
                        )
                        return@post
                    }

                    if (DiagnosticsCache.text == text &&
                        binding.diagnosticText.text.toString() == text
                    ) {
                        return@post
                    }
                    DiagnosticsCache.text = text
                    // 文本长度变化会影响卡片高度，带动画过渡避免跳变。
                    LayoutAnimator.animateIfEnabled(binding.contentContainer) {
                        binding.diagnosticText.text = text
                    }
                }
            }
        }

        if (!started) {
            // Shizuku 不可用。同样重试若干次——Shizuku 可能正在启动中，
            // 首次查询失败不代表真的不可用。
            if (attempt < DIAG_RETRY_DELAYS_MS.size) {
                binding.diagnosticText.postDelayed(
                    { refreshDiagnostics(attempt + 1) },
                    DIAG_RETRY_DELAYS_MS[attempt]
                )
                return
            }
            val text = getString(R.string.diag_not_bound)
            if (binding.diagnosticText.text.toString() != text) {
                LayoutAnimator.animateIfEnabled(binding.contentContainer) {
                    binding.diagnosticText.text = text
                }
            }
        }
    }

    /** 执行一次诊断查询，返回要展示的文本。运行在后台线程。 */
    private fun queryDiagnostics(): String {
        if (!shizuku.isReady()) return getString(R.string.diag_not_bound)

        val registered = shizuku.isListenerRegistered()
        val error = shizuku.registerError()

        // 读取状态用三态判断：空剪切板是正常情况，不能算作故障。
        val readState = when (shizuku.readClipboardDetailed()) {
            is ClipboardReadResult.Success -> ReadState.Ok
            ClipboardReadResult.Empty -> ReadState.Empty
            ClipboardReadResult.Failure -> ReadState.Failed
        }

        return buildString {
            appendLine(getString(R.string.diag_shizuku_ok))
            appendLine(
                getString(
                    if (registered) R.string.diag_listener_ok else R.string.diag_listener_failed
                )
            )
            append(
                getString(
                    when (readState) {
                        ReadState.Ok -> R.string.diag_read_ok
                        ReadState.Empty -> R.string.diag_read_empty
                        ReadState.Failed -> R.string.diag_read_failed
                    }
                )
            )
            // 注册失败时把具体原因也显示出来，免去抓 logcat 的步骤。
            if (!registered && !error.isNullOrBlank()) {
                appendLine()
                append(getString(R.string.diag_reason, error))
            }
        }
    }

    /** 剪切板读取的三态结果。 */
    private enum class ReadState { Ok, Empty, Failed }

    /**
     * 手动重新检测。
     *
     * 与自动刷新的区别：
     * - 清空缓存，强制走一次完整查询（自动刷新会跳过结果未变的情况）
     * - 按钮进入短暂禁用态，给出"正在检测"的即时反馈
     * - 断开已有连接后重连，以便修正"连接存在但状态已失效"的情况
     */
    private fun recheckDiagnostics() {
        if (resolveState() != DisplayState.Ready) {
            toast(getString(R.string.recheck_need_running))
            return
        }

        DiagnosticsCache.clear()
        binding.refreshDiagnosticButton.isEnabled = false
        binding.diagnosticText.text = getString(R.string.diag_checking)

        // 断开后重连：仅靠已有的连接查询可能读到陈旧状态，
        // 重建连接能真实反映 UserService 当前是否可用。
        shizuku.unbindLocal()

        binding.diagnosticText.postDelayed({
            refreshDiagnostics()
            // 给足重试窗口后再恢复按钮，避免用户连点导致查询叠加。
            binding.diagnosticText.postDelayed(
                { binding.refreshDiagnosticButton.isEnabled = true },
                DIAG_RECHECK_ENABLE_DELAY_MS
            )
        }, DIAG_RECHECK_RESTART_DELAY_MS)
    }

    private val diagnosticsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onDestroy() {
        diagnosticsExecutor.shutdownNow()
        // 只解除本界面的绑定，不销毁 UserService 进程——
        // 前台服务可能正在使用同一个 daemon 进程进行监测。
        shizuku.unbindLocal()
        super.onDestroy()
    }

    /**
     * 综合「用户是否开启服务」与「Shizuku 可用性」得出展示状态。
     */
    /**
     * 综合「服务是否真在运行」与「Shizuku 可用性」得出展示状态。
     *
     * 注意这里用的是 [ClipboardMonitorService.isRunning]（进程内的真实标记），
     * 而不是 [Prefs.monitoringEnabled]。后者只是"用户上次的意图"——
     * 服务被系统回收后它仍为 true，只看它会把"已停止"误报成"监测中"。
     */
    private fun resolveState(): DisplayState {
        // 用户没有开启过监测，直接是停止态。
        if (!prefs.monitoringEnabled) return DisplayState.Stopped

        // 开启过但服务已不在运行：服务被回收，或正在启动的瞬间。
        // 交给 Shizuku 状态判断具体原因，但不显示为"监测中"。
        val running = ClipboardMonitorService.isRunning
        return when (shizuku.currentState()) {
            ShizukuHelper.State.NOT_INSTALLED -> DisplayState.NotInstalled
            ShizukuHelper.State.NOT_RUNNING -> DisplayState.NotRunning
            ShizukuHelper.State.NO_PERMISSION -> DisplayState.NoPermission
            // 只有服务确实在跑才算就绪。
            ShizukuHelper.State.READY ->
                if (running) DisplayState.Ready else DisplayState.Stopped
        }
    }

    private fun applyState(state: DisplayState) {
        binding.stateText.setText(state.titleRes)
        binding.stateDesc.setText(state.descRes)
        binding.stateDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, state.colorRes)
        )

        binding.primaryAction.setText(state.actionRes)

        // 仅"未安装/未运行"时给出打开 Shizuku 的次要入口。
        val showSecondary = state == DisplayState.NotInstalled || state == DisplayState.NotRunning
        binding.secondaryAction.visibility = if (showSecondary) View.VISIBLE else View.GONE
        binding.secondaryAction.setText(
            if (state == DisplayState.NotInstalled) R.string.action_install_shizuku
            else R.string.action_open_shizuku
        )

        binding.capturedCount.visibility =
            if (state == DisplayState.Ready) View.VISIBLE else View.GONE
    }

    private fun updateCapturedCount() {
        binding.capturedCount.text = getString(R.string.status_running_desc, prefs.capturedCount)
    }

    // ------------------------------------------------------------ 交互

    private fun onPrimaryAction() {
        when (resolveState()) {
            DisplayState.Stopped -> startMonitoring()

            DisplayState.Ready -> stopMonitoring()

            DisplayState.NoPermission -> {
                // 权限请求需要在 Android 前台流程里走，这里直接调 Shizuku。
                val requested = shizuku.requestPermissionAndBind()
                if (requested) {
                    startMonitoring()
                } else if (!shizuku.isBinderAlive()) {
                    toast(getString(R.string.reason_shizuku_not_running))
                }
                // 授权结果通过 ActivityResult 以外的监听回调体现，
                // 稍后刷新一次；状态会从"未授权"变为"监测中"，带动画过渡。
                binding.root.postDelayed({ refresh(animated = true) }, 800)
            }

            DisplayState.NotRunning -> {
                toast(getString(R.string.reason_shizuku_not_running))
                openShizukuApp()
            }

            DisplayState.NotInstalled -> openShizukuApp()
        }
    }

    private fun startMonitoring() {
        if (!hasNotificationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
            // 权限结果回来后用户需要再点一次；这里不自动继续，避免流程错乱。
            return
        }
        // 服务状态即将改变，旧诊断结果不再有效。
        DiagnosticsCache.clear()
        ClipboardMonitorService.start(this)
        prefs.monitoringEnabled = true
        // 先立刻按"已启动"刷新一次（带动画），不必等服务的实际状态回传，
        // 否则用户点完会有明显停顿感。
        binding.root.post { refresh(animated = true) }
        // 服务真正就绪后再刷一次，修正可能的状态差异。
        binding.root.postDelayed({ refresh(animated = true) }, 700)
    }

    private fun stopMonitoring() {
        DiagnosticsCache.clear()
        ClipboardMonitorService.stop(this)
        prefs.monitoringEnabled = false
        binding.root.post { refresh(animated = true) }
    }

    /**
     * 测试按钮：走一遍完整识别流程。
     *
     * Shizuku 可用时经 shell 身份读；否则退回直接读（此时本 Activity 有焦点，能读到）。
     */
    private fun testClipboard() {
        val text = if (shizuku.isReady()) {
            shizuku.readClipboardOnce()
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
        } else {
            readClipboardDirectly()
        }

        if (text.isNullOrBlank()) {
            toast(getString(R.string.no_link_desc))
            return
        }

        val url = UrlExtractor.extractFirst(text)
        if (url == null) {
            toast(getString(R.string.no_link_desc))
            return
        }

        LinkRepository(this).add(url, source = "test")
        NotificationHelper(this).showLinkFound(url)
        toast(url)
    }

    /** 本 Activity 处于前台且持有焦点，此时直接读是允许的。 */
    private fun readClipboardDirectly(): String? = runCatching {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(this).toString()
    }.getOrNull()

    // ------------------------------------------------------------ 权限任务

    /**
     * Shizuku 任务的按钮：未安装/未运行跳 Shizuku 应用，未授权走授权流程。
     */
    private fun onShizukuTaskAction() {
        when (shizuku.currentState()) {
            ShizukuHelper.State.NOT_INSTALLED, ShizukuHelper.State.NOT_RUNNING -> openShizukuApp()

            ShizukuHelper.State.NO_PERMISSION -> {
                val requested = shizuku.requestPermissionAndBind()
                if (!requested && !shizuku.isBinderAlive()) {
                    toast(getString(R.string.reason_shizuku_not_running))
                }
                // 授权结果经 Shizuku 监听回调体现，稍后刷新一次。
                binding.root.postDelayed({ refresh(animated = true) }, 800)
            }

            ShizukuHelper.State.READY -> Unit
        }
    }

    /** 通知任务的按钮：按当前待办状态分发到对应动作。 */
    private fun onNotificationTaskAction() {
        if (!notifications.areNotificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermission()
            } else if (!notifications.openAppNotificationSettings()) {
                toast(getString(R.string.notification_settings_unavailable))
            }
            return
        }
        if (!notifications.isChannelHeadsUpCapable()) {
            // 渠道重要性不足：渠道页是调整它的唯一入口，所以这里跳渠道页。
            if (!notifications.openChannelSettingsAndFallback()) {
                toast(getString(R.string.notification_settings_unavailable))
            }
            return
        }
        runBannerTest()
    }

    /**
     * 横幅实测确认：发一条与真实提醒同路径的测试通知，再问用户是否看到。
     *
     * 看到 → 记入 Prefs（ROM 开关无法读取，确认一次后长期有效）；
     * 没看到 → 多半是 ROM 的应用级「横幅」开关没开，跳应用通知设置页
     * （那个开关不在渠道页里，渠道开关未开时渠道页也不显示横幅选项）。
     */
    private fun runBannerTest() {
        notifications.showBannerTest()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.banner_test_dialog_title)
            .setMessage(R.string.banner_test_dialog_message)
            .setPositiveButton(R.string.banner_test_seen) { _, _ ->
                prefs.bannerConfirmed = true
                refresh(animated = true)
            }
            .setNegativeButton(R.string.banner_test_missed) { _, _ ->
                if (!notifications.openAppNotificationSettings()) {
                    toast(getString(R.string.notification_settings_unavailable))
                }
            }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * 是否已加入电池优化白名单。
     *
     * 获取不到 PowerManager 时返回 true 而非 false：无法判断不等于"未允许"，
     * 避免把系统异常误报成用户配置问题。
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * 请求电池优化豁免。
     *
     * ## 为什么选标准豁免而不是 ROM 的后台权限
     * 国产 ROM 的自启动/后台行为开关没有公开 API，既无法检测也无法申请；
     * 标准的电池优化豁免既能检测状态、又能一键弹出系统确认框，
     * 是唯一能接入任务清单的「后台行为」。
     *
     * 部分 ROM 移除了直连确认框，此时退回电池优化列表页让用户手动设置。
     */
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        if (tryStartActivity(direct)) return

        if (tryStartActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            toast(getString(R.string.battery_list_hint))
            return
        }
        toast(getString(R.string.settings_open_failed))
    }

    private fun tryStartActivity(intent: Intent): Boolean =
        runCatching { startActivity(intent) }.isSuccess

    // ------------------------------------------------------------ 权限

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openShizukuApp() {
        val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launch != null) {
            runCatching { startActivity(launch) }
                .onFailure { toast(getString(R.string.action_install_shizuku)) }
            return
        }
        // 未安装：跳应用商店页面。
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(market) }.onFailure {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(web) }
                .onFailure { toast(getString(R.string.reason_shizuku_not_installed)) }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 界面状态机，把「服务开关」与「Shizuku 状态」合成一个可展示的状态。 */
    private enum class DisplayState(
        val titleRes: Int,
        val descRes: Int,
        val actionRes: Int,
        val colorRes: Int
    ) {
        Stopped(
            R.string.state_stopped, R.string.state_desc_stopped,
            R.string.action_start, R.color.on_surface_variant
        ),
        Ready(
            R.string.state_ready, R.string.state_desc_ready,
            R.string.action_stop, R.color.state_ok
        ),
        NoPermission(
            R.string.state_no_permission, R.string.state_desc_no_permission,
            R.string.action_request_permission, R.color.state_warn
        ),
        NotRunning(
            R.string.state_not_running, R.string.state_desc_not_running,
            R.string.action_open_shizuku, R.color.state_error
        ),
        NotInstalled(
            R.string.state_not_installed, R.string.state_desc_not_installed,
            R.string.action_install_shizuku, R.color.state_error
        )
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /**
         * 诊断查询的重试延迟。
         *
         * 绑定 UserService 是异步的，首次查询可能落在绑定完成之前而读到"未就绪"。
         * 逐次延长间隔重试，既避免界面闪出中间态，也不会造成密集轮询。
         */
        val DIAG_RETRY_DELAYS_MS = longArrayOf(300L, 600L, 1200L)

        /** 手动重检时，先断开旧连接再重连的等待时间。 */
        const val DIAG_RECHECK_RESTART_DELAY_MS = 250L

        /** 手动重检后按钮恢复可用的时间，覆盖完整的重试窗口。 */
        const val DIAG_RECHECK_ENABLE_DELAY_MS = 3000L
    }
}
