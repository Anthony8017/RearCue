package com.rearcue.poc.rear

import com.rearcue.poc.core.ContentPage

/**
 * 会话列表与通知页之间的无正文闪现切换判定。
 *
 * 列表去通知页：目标通知页从第一帧铺底，旧列表自身淡出；
 * 通知页回列表：旧通知页保底，新列表自身淡入。两条路径都不经过 Agent 正文。
 * 普通通知页/Agent 页切换仍保留既有交叉淡入淡出。
 *
 * 快照上的一次性标记（[AgentPageSurface.immediateTransition]）**只**用来决定内容层要不要
 * 直接换页（[isImmediatePickerPageChange]，它不组任何图层）；列表层的挂/撤与淡入淡出触发
 * 一律看「相邻快照差」（[isPickerLayerComposed]），因为那个标记要等下一次发布才复位。
 */
internal fun isPickerExitSnapshot(current: AgentPageSurface): Boolean =
    current.immediateTransition &&
        current.contentPage == ContentPage.NOTIFICATION &&
        !current.pickerOpen

internal fun isPickerToNotification(
    previous: AgentPageSurface,
    current: AgentPageSurface,
): Boolean = previous.contentPage == ContentPage.AGENT &&
    previous.pickerOpen &&
    current.contentPage == ContentPage.NOTIFICATION &&
    !current.pickerOpen

internal fun isPickerEnterTransition(
    previous: AgentPageSurface,
    current: AgentPageSurface,
): Boolean = previous.contentPage == ContentPage.NOTIFICATION &&
    !previous.pickerOpen &&
    current.contentPage == ContentPage.AGENT &&
    current.pickerOpen

/**
 * 只有“列表去通知页”需要把内容层直接换到通知页，配合旧列表淡出；
 * 通知页回列表由列表淡入覆盖，因此不应走内容层直切。
 */
internal fun isImmediatePickerPageChange(
    previous: AgentPageSurface,
    current: AgentPageSurface,
): Boolean = isPickerToNotification(previous, current)

/**
 * 列表层是否参与组合：只在**这一次**进出过渡期间为真（[fading] 是淡入/淡出动画进行中）。
 *
 * 为什么不能拿 [AgentPageSurface.immediateTransition] 当挂载条件（2026-10-04 修）：那个标记是
 * 「下一次发布前有效」的一次性标记，而列表层退场结束时 alpha 已经到 0——层照旧铺满全屏吃掉
 * 点按，且吃掉点按就不会再有事件来发布、标记也就永远不复位：屏变成「看得见、点不动」。实机表现
 * 是机主点通知页空白处想回 Agent 页，连点 12 次一条 `rear-tap received` 都没有（14:25:59 起），
 * 一直等到下一次外部事件发布才恢复。判据只留「相邻快照差 + 本地动画标志」这两条自过期条件。
 */
internal fun isPickerLayerComposed(
    previous: AgentPageSurface,
    current: AgentPageSurface,
    fading: Boolean,
): Boolean =
    fading || isPickerToNotification(previous, current) || isPickerEnterTransition(previous, current)

/** 切页首帧先落在正确起点；LaunchedEffect 尚未启动时不能读上一轮动画的终点。 */
internal fun pickerLayerAlpha(
    previous: AgentPageSurface,
    current: AgentPageSurface,
    fadingIn: Boolean,
    fadingOut: Boolean,
    enterAlpha: Float,
    exitAlpha: Float,
): Float = when {
    isPickerEnterTransition(previous, current) && !fadingIn -> 0f
    isPickerToNotification(previous, current) && !fadingOut -> 1f
    current.pickerOpen -> enterAlpha
    else -> exitAlpha
}
