package com.rearcue.poc.rear

import com.rearcue.poc.core.ContentPage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentPageTransitionPolicyTest {

    private fun surface(
        page: ContentPage?,
        picker: Boolean,
    ) = AgentPageSurface(page, picker, emptyList())

    @Test
    fun `列表内去通知页与返回列表都直接切换`() {
        assertTrue(
            isImmediatePickerPageChange(
                surface(ContentPage.AGENT, picker = true),
                surface(ContentPage.NOTIFICATION, picker = false),
            ),
        )
        assertTrue(
            isImmediatePickerPageChange(
                surface(ContentPage.NOTIFICATION, picker = false),
                surface(ContentPage.AGENT, picker = true),
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
}
