package com.rearcue.poc.rear

import com.rearcue.poc.core.ContentPage

/**
 * 会话列表与内容页的无闪现切换判定：只针对“列表内去通知页”和“通知页返回列表”。
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

internal fun isImmediatePickerPageChange(
    previous: AgentPageSurface,
    current: AgentPageSurface,
): Boolean {
    val pickerToNotification = previous.contentPage == ContentPage.AGENT &&
        previous.pickerOpen &&
        current.contentPage == ContentPage.NOTIFICATION &&
        !current.pickerOpen
    val notificationToPicker = previous.contentPage == ContentPage.NOTIFICATION &&
        !previous.pickerOpen &&
        current.contentPage == ContentPage.AGENT &&
        current.pickerOpen
    return pickerToNotification || notificationToPicker
}
