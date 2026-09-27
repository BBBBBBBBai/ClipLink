package com.cliplink

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 全面屏（edge-to-edge）适配。
 *
 * ## 为什么必须处理
 * Android 15（API 35）起，targetSdk >= 35 的应用被**强制** edge-to-edge：
 * 内容默认延伸到状态栏与导航栏之后，且 `android:statusBarColor` /
 * `android:navigationBarColor` 变为空操作（设了也不生效）。
 * 不做 inset 处理的话，顶部标题会被状态栏压住、底部按钮会被导航栏遮住。
 *
 * ## 两种处理模式
 * - [applyToRoot]：整页留白。适用于内容型页面（如主界面、设置页）。
 * - [applyWithToolbar]：顶栏背景延伸到状态栏下、内容下移；底部同样处理。
 *   适用于带 Toolbar 的页面（如历史记录）。
 *
 * ## 为什么不直接给底部按钮加 padding
 * padding 是视图**内部**的内边距，加在按钮上只会让文字上移，
 * 按钮本身的边界与可点击区域仍延伸进导航栏。正确做法是把底部内边距
 * 加在**容器**上，让按钮整体被推上去。
 *
 * ## 为什么不用 fitsSystemWindows
 * `android:fitsSystemWindows="true"` 是旧机制，与 edge-to-edge 配合时
 * 行为不一致（某些场景不生效或过度留白）。现代做法是显式监听 inset。
 *
 * padding 每次都是**基于初始值重算**而非累加，因此键盘弹出、旋转、
 * 切换深色模式等引起 inset 变化时不会重复叠加。
 */
object InsetsHelper {

    /** 一次取全需要的 inset：系统栏 + 刘海/挖孔。 */
    private fun insetsOf(windowInsets: WindowInsetsCompat) =
        windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )

    /**
     * 整页模式：给根布局加四边内边距。
     *
     * 根布局的背景会填满整个屏幕（含系统栏区域），内容则内缩到安全区内。
     *
     * @param extraTop    系统栏之外额外增加的顶部间距
     * @param extraBottom 系统栏之外额外增加的底部间距
     */
    fun applyToRoot(
        activity: Activity,
        root: View,
        extraTop: Int = 0,
        extraBottom: Int = 0
    ) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        // 以初始 padding 为基准，避免把上一次的计算结果当基准而累加。
        val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = insetsOf(windowInsets)
            view.updatePadding(
                left = base[0] + bars.left,
                top = base[1] + bars.top + extraTop,
                right = base[2] + bars.right,
                bottom = base[3] + bars.bottom + extraBottom
            )
            windowInsets
        }

        ViewCompat.requestApplyInsets(root)
    }

    /**
     * Toolbar 模式：顶栏背景延伸到状态栏下，内容整体避开导航栏。
     *
     * ## 为什么给 Toolbar 增高而不是加 padding
     * Toolbar 的高度是固定的 `?attr/actionBarSize`。若只加 `paddingTop`，
     * 内容区会被压缩成 `apkHeight - statusBarHeight`，标题被挤掉或显示不全。
     * 正确做法是**同时增加高度**，让内容区保持原有尺寸、整体下移。
     *
     * - `toolbar`：高度增加状态栏高度，并加等量 paddingTop。
     * - `root`：加 bottom 内边距，让底部按钮/列表避开导航栏，
     *   同时根布局背景填满导航栏区域。
     *
     * @param root 页面根容器，用于承载底部内边距
     */
    fun applyWithToolbar(activity: Activity, toolbar: View, root: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        val toolbarBase = intArrayOf(
            toolbar.paddingLeft, toolbar.paddingTop, toolbar.paddingRight
        )
        val toolbarBaseHeight = toolbar.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val rootBaseBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, windowInsets ->
            val bars = insetsOf(windowInsets)

            // 高度与顶部内边距同步增加，内容区尺寸不变、整体下移。
            // 高度按基准值重算而非累加，避免多次 inset 分发后越来越高。
            val params = view.layoutParams
            if (params != null && toolbarBaseHeight != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = toolbarBaseHeight + bars.top
                view.layoutParams = params
            }
            view.updatePadding(
                left = toolbarBase[0] + bars.left,
                top = toolbarBase[1] + bars.top,
                right = toolbarBase[2] + bars.right
            )
            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = insetsOf(windowInsets)
            view.updatePadding(bottom = rootBaseBottom + bars.bottom)
            windowInsets
        }

        ViewCompat.requestApplyInsets(root)
        ViewCompat.requestApplyInsets(toolbar)
    }

    /**
     * 是否运行在强制 edge-to-edge 的版本上（Android 15 / API 35）。
     *
     * 仅用于日志与诊断，逻辑上无需据此分支——inset 监听在旧版本上同样正确工作。
     */
    fun isEdgeToEdgeEnforced(): Boolean = Build.VERSION.SDK_INT >= 35
}
