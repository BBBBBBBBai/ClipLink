package com.cliplink

import android.animation.TimeInterpolator
import android.provider.Settings
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * 状态卡片的布局变化动画。
 *
 * ## 解决什么问题
 * 「运行状态」卡片里有多处内容会随状态变化：状态文字长度变化、已捕获条数显示/隐藏、
 * 次要按钮出现/消失、诊断区出现/消失。这些同时发生时卡片高度会**突变**，
 * 下方卡片的位置也跟着瞬移，观感很生硬。
 *
 * ## 做法
 * 用 [TransitionManager.beginDelayedTransition] 对容器做过渡，组合三种效果：
 * - [ChangeBounds]：捕获卡片高度变化与下方卡片的位移——这是平滑的关键
 * - [Fade]：新出现/消失的视图淡入淡出，而不是硬切
 *
 * 过渡以**内容容器**为 sceneRoot 而非单个卡片：卡片高度变化会推挤下方视图，
 * 只有让容器整体参与，下方卡片的位移才会一起平滑过渡。
 */
object LayoutAnimator {

    /** 过渡时长。太短看不出效果，太长会拖慢操作反馈。 */
    private const val DURATION_MS = 260L

    private val interpolator: TimeInterpolator = AccelerateDecelerateInterpolator()

    /**
     * 在修改布局前调用，让接下来的布局变化带动画。
     *
     * 用法：
     * ```
     * LayoutAnimator.animateIfEnabled(binding.contentContainer) {
     *     binding.stateText.text = "..."
     *     binding.capturedCount.visibility = View.VISIBLE
     * }
     * ```
     *
     * ## 为什么需要 Fade
     * `ChangeBounds` 只对**位置和尺寸发生变化**的视图做动画。对于"从无到有"的视图
     * （如诊断区从 GONE 变 VISIBLE），它在变化前没有可插值的起始状态，
     * 会直接出现而不是平滑过渡。`Fade` 负责这类视图的淡入淡出。
     *
     * @param sceneRoot 参与过渡的容器（传内容根布局，不是单个卡片）
     */
    fun animate(sceneRoot: ViewGroup, block: () -> Unit) {
        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            // ChangeBounds 处理高度变化与下方视图的位移。
            addTransition(ChangeBounds())
            // Fade 处理出现/消失的视图，避免硬切。
            addTransition(Fade().apply { duration = DURATION_MS })
            duration = DURATION_MS
            interpolator = this@LayoutAnimator.interpolator
        }

        TransitionManager.beginDelayedTransition(sceneRoot, transition)
        block()
    }

    /**
     * 按系统无障碍设置决定是否播放过渡。
     *
     * 在开发者选项或无障碍设置里关闭动画的用户（例如对位移敏感的前庭功能障碍者）
     * 不应看到这类过渡，此时直接切换布局。
     *
     * @return 系统动画缩放为 0 时返回 false
     */
    fun shouldAnimate(root: View): Boolean = runCatching {
        Settings.Global.getFloat(
            root.context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }.getOrDefault(true)

    /** 便捷封装：按无障碍设置决定是否带动画。 */
    fun animateIfEnabled(sceneRoot: ViewGroup, block: () -> Unit) {
        if (shouldAnimate(sceneRoot)) animate(sceneRoot, block) else block()
    }
}
