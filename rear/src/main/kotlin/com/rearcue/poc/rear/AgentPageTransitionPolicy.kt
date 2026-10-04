package com.rearcue.poc.rear

import com.rearcue.poc.core.ContentPage

/**
 * 会话列表与通知页之间的无正文闪现切换判定。
 *
 * 列表去通知页：目标通知页从第一帧铺底，旧列表自身淡出；
 * 通知页回列表：旧通知页保底，新列表自身淡入。两条路径都不经过 Agent 正文。
 * 普通通知页/Agent 页切换仍保留既有交叉淡入淡出。
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

internal fun isPickerEnterSnapshot(current: AgentPageSurface): Boolean =
    current.immediateTransition &&
        current.contentPage == ContentPage.AGENT &&
        current.pickerOpen

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
