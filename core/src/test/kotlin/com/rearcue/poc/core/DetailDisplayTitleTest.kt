package com.rearcue.poc.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Detail 标题行显示口径（grill #89：正文界面不显示软件名称）的纯函数判例。 */
class DetailDisplayTitleTest {

    @Test
    fun `标题与应用名同值且正文非空时省略标题行`() {
        assertEquals("", detailDisplayTitle("飞书", appLabel = "飞书", text = "命命: 实机验收 9/9 PASS"))
    }

    @Test
    fun `标题是联系人或群名时照常显示`() {
        assertEquals("张三", detailDisplayTitle("张三", appLabel = "微信", text = "在吗？"))
        assertEquals("家庭群", detailDisplayTitle("家庭群", appLabel = "微信", text = "晚上回家吃饭"))
    }

    @Test
    fun `正文为空时保留标题（整卡不能空）`() {
        assertEquals("飞书", detailDisplayTitle("飞书", appLabel = "飞书", text = "   "))
    }

    @Test
    fun `应用名解析不到时照常显示`() {
        assertEquals("飞书", detailDisplayTitle("飞书", appLabel = null, text = "内容"))
    }

    @Test
    fun `大小写与首尾空白差异不误判，包含关系不算同值`() {
        assertEquals("", detailDisplayTitle(" Lark ", appLabel = "lark", text = "内容"))
        assertEquals("飞书消息", detailDisplayTitle("飞书消息", appLabel = "飞书", text = "内容"))
    }
}
