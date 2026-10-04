package com.rearcue.poc.rear

import com.rearcue.poc.core.ContentPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentPageTransitionPolicyTest {

    @Test
    fun `通知回列表首帧透明_不能先画上轮全亮终点`() {
        assertEquals(
            0f,
            pickerLayerAlpha(
                previous = surface(ContentPage.NOTIFICATION, picker = false),
                current = surface(ContentPage.AGENT, picker = true),
                fadingIn = false,
                fadingOut = false,
                enterAlpha = 1f,
                exitAlpha = 0f,
            ),
        )
    }

    @Test
    fun `再次退出列表首帧全亮_不能露出正文或通知一帧`() {
        assertEquals(
            1f,
            pickerLayerAlpha(
                previous = surface(ContentPage.AGENT, picker = true),
                current = surface(ContentPage.NOTIFICATION, picker = false),
                fadingIn = false,
                fadingOut = false,
                enterAlpha = 1f,
                exitAlpha = 0f,
            ),
        )
    }

    @Test
    fun `渐变启动后跟随动画进度_不被相邻快照仍有差异重置`() {
        val notification = surface(ContentPage.NOTIFICATION, picker = false)
        val picker = surface(ContentPage.AGENT, picker = true)
        for (alpha in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(
                alpha,
                pickerLayerAlpha(notification, picker, true, false, alpha, 0f),
            )
            assertEquals(
                alpha,
                pickerLayerAlpha(picker, notification, false, true, 1f, alpha),
            )
        }
        // 从正文直接打开列表、新实例首次就显示列表：均不凭空补演淡入。
        assertEquals(1f, pickerLayerAlpha(picker, picker, false, false, 1f, 0f))
        assertEquals(
            1f,
            pickerLayerAlpha(surface(ContentPage.AGENT, picker = false), picker, false, false, 1f, 0f),
        )
    }

    private fun surface(
        page: ContentPage?,
        picker: Boolean,
    ) = AgentPageSurface(page, picker, emptyList())

    @Test
    fun `列表内去通知页直接切换_返回列表带渐变`() {
        assertTrue(
            isImmediatePickerPageChange(
                surface(ContentPage.AGENT, picker = true),
                surface(ContentPage.NOTIFICATION, picker = false),
            ),
        )
        assertFalse(
            isImmediatePickerPageChange(
                surface(ContentPage.NOTIFICATION, picker = false),
                surface(ContentPage.AGENT, picker = true),
            ),
        )
    }

    @Test
    fun `通知页回列表识别为列表进入`() {
        assertTrue(
            isPickerEnterTransition(
                surface(ContentPage.NOTIFICATION, picker = false),
                surface(ContentPage.AGENT, picker = true),
            ),
        )
        assertFalse(
            isPickerEnterTransition(
                surface(ContentPage.AGENT, picker = true),
                surface(ContentPage.NOTIFICATION, picker = false),
            ),
        )
    }

    @Test
    fun `当前快照明确标记列表退场时首帧即保留旧列表`() {
        assertTrue(
            isPickerExitSnapshot(
                AgentPageSurface(
                    contentPage = ContentPage.NOTIFICATION,
                    pickerOpen = false,
                    pickerRows = emptyList(),
                    immediateTransition = true,
                ),
            ),
        )
        assertFalse(
            isPickerExitSnapshot(
                AgentPageSurface(
                    contentPage = ContentPage.NOTIFICATION,
                    pickerOpen = false,
                    pickerRows = emptyList(),
                    immediateTransition = false,
                ),
            ),
        )
    }

    @Test
    fun `列表去通知页判定只认列表退场`() {
        assertTrue(
            isPickerToNotification(
                surface(ContentPage.AGENT, picker = true),
                surface(ContentPage.NOTIFICATION, picker = false),
            ),
        )
        assertFalse(
            isPickerToNotification(
                surface(ContentPage.AGENT, picker = false),
                surface(ContentPage.NOTIFICATION, picker = false),
            ),
        )
    }

    @Test
    fun `普通内容页切换仍走交叉淡入淡出`() {
        assertFalse(
            isImmediatePickerPageChange(
                surface(ContentPage.AGENT, picker = false),
                surface(ContentPage.NOTIFICATION, picker = false),
            ),
        )
        assertFalse(
            isImmediatePickerPageChange(
                surface(ContentPage.NOTIFICATION, picker = false),
                surface(ContentPage.AGENT, picker = false),
            ),
        )
    }

    private fun exitSnapshot() = AgentPageSurface(
        contentPage = ContentPage.NOTIFICATION,
        pickerOpen = false,
        pickerRows = emptyList(),
        immediateTransition = true,
    )

    @Test
    fun `列表层只在过渡期间在屏_一次性标记不挂层`() {
        val stale = exitSnapshot()
        // 退场结束、下一次发布还没到：层必须已经撤掉。留着就是一层全透明列表铺满屏吃掉点按
        // （2026-10-04 机主报「点通知页空白处回 Agent 页无效」的真因）。
        assertFalse(isPickerLayerComposed(previous = stale, current = stale, fading = false))
    }

    @Test
    fun `列表层过渡首帧与动画进行中照旧在屏`() {
        val exit = exitSnapshot()
        assertTrue(
            isPickerLayerComposed(
                previous = surface(ContentPage.AGENT, picker = true),
                current = exit,
                fading = false,
            ),
        )
        assertTrue(isPickerLayerComposed(previous = exit, current = exit, fading = true))
        assertTrue(
            isPickerLayerComposed(
                previous = surface(ContentPage.NOTIFICATION, picker = false),
                current = surface(ContentPage.AGENT, picker = true),
                fading = false,
            ),
        )
    }

    @Test
    fun `普通内容页切换不挂列表层`() {
        val notification = surface(ContentPage.NOTIFICATION, picker = false)
        assertFalse(isPickerLayerComposed(previous = notification, current = notification, fading = false))
        assertFalse(
            isPickerLayerComposed(
                previous = surface(ContentPage.AGENT, picker = false),
                current = notification,
                fading = false,
            ),
        )
    }
}
