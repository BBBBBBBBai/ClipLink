package com.cliplink

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cliplink.databinding.ActivitySettingsBinding

/**
 * 设置页：行为开关与忽略域名管理。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        // 全面屏适配：工具栏背景延伸到状态栏下，内容避开导航栏。
        // 工具栏位于滚动容器之外（固定顶部），因此可以安全地用 Toolbar 模式。
        InsetsHelper.applyWithToolbar(
            activity = this,
            toolbar = binding.toolbar,
            root = binding.root
        )

        binding.toolbar.setNavigationOnClickListener { finish() }

        // 初始值。
        binding.autoOpenSwitch.isChecked = prefs.autoOpen
        binding.wifiOnlySwitch.isChecked = prefs.wifiOnly
        binding.chargingOnlySwitch.isChecked = prefs.chargingOnly

        binding.autoOpenSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.autoOpen = checked
        }
        binding.wifiOnlySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.wifiOnly = checked
        }
        binding.chargingOnlySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.chargingOnly = checked
        }

        // 焦点通知（流体云）：开关 + 停留时长滑块。
        binding.focusNotificationSwitch.isChecked = prefs.focusNotification
        binding.focusDurationValue.text =
            getString(R.string.focus_duration_seconds, prefs.focusTimeoutSeconds)
        binding.focusDurationSlider.value = prefs.focusTimeoutSeconds.toFloat()
        binding.focusDurationSlider.setLabelFormatter {
            getString(R.string.focus_duration_seconds, it.toInt())
        }
        setFocusDurationVisible(prefs.focusNotification, animated = false)

        binding.focusNotificationSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.focusNotification = checked
            setFocusDurationVisible(checked)
            if (checked) onFocusNotificationEnabled()
        }
        binding.focusDurationSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.focusTimeoutSeconds = value.toInt()
            binding.focusDurationValue.text =
                getString(R.string.focus_duration_seconds, value.toInt())
        }

        binding.addIgnoredButton.setOnClickListener { showAddDomainDialog() }
    }

    override fun onResume() {
        super.onResume()
        renderIgnoredDomains()
    }

    /**
     * 展开/收起「停留时长」区域。
     *
     * 用 [LayoutAnimator] 以整个设置容器为 sceneRoot 做过渡：
     * ChangeBounds 让卡片高度与下方卡片的位移一起平滑，Fade 负责内容淡入淡出。
     * 首次进入界面按已有状态直接就位，不播动画（与主界面同理）。
     */
    private fun setFocusDurationVisible(visible: Boolean, animated: Boolean = true) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (binding.focusDurationGroup.visibility == target) return

        if (animated) {
            LayoutAnimator.animateIfEnabled(binding.settingsContainer) {
                binding.focusDurationGroup.visibility = target
            }
        } else {
            binding.focusDurationGroup.visibility = target
        }
    }

    /**
     * 焦点通知打开时的引导。
     *
     * Android 16+ 要求用户在系统设置里允许本应用的「实时更新」，
     * 未允许则提示并跳转对应设置页；旧系统提示会自动退回普通通知。
     */
    private fun onFocusNotificationEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            toast(getString(R.string.focus_unsupported_hint))
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.canPostPromotedNotifications() != false) return

        toast(getString(R.string.focus_need_live_updates))
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        runCatching { startActivity(intent) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun renderIgnoredDomains() {
        val domains = prefs.ignoredDomains.sorted()
        val container = binding.ignoredContainer
        container.removeAllViews()

        binding.ignoredEmpty.visibility = if (domains.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        domains.forEach { domain ->
            val row = inflater.inflate(R.layout.item_ignored_domain, container, false)
            row.findViewById<TextView>(R.id.domainText).text = domain
            row.findViewById<View>(R.id.removeButton).setOnClickListener {
                prefs.ignoredDomains = prefs.ignoredDomains - domain
                renderIgnoredDomains()
            }
            container.addView(row)
        }
    }

    private fun showAddDomainDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.ignored_add_hint)
            setSingleLine()
        }

        // 用带内边距的容器包裹，避免输入框贴着对话框边缘。
        val wrapper = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.ignored_add_title)
            .setView(wrapper)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val domain = sanitizeDomain(input.text.toString())
                if (domain != null) {
                    prefs.ignoredDomains = prefs.ignoredDomains + domain
                    renderIgnoredDomains()
                }
            }
            .show()
    }

    /**
     * 把用户输入整理成裸域名。
     *
     * 允许直接粘贴完整链接，这里会剥掉协议、路径与 `www.` 前缀。
     */
    private fun sanitizeDomain(raw: String): String? {
        var value = raw.trim().lowercase()
        if (value.isEmpty()) return null

        value = value.substringAfter("://", value)
        value = value.substringBefore('/')
        value = value.substringBefore('?')
        value = value.substringBefore(':')
        value = value.removePrefix("www.")

        // 至少要有一个点，且各段非空。
        val labels = value.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        if (value.contains(' ')) return null

        return value
    }
}
