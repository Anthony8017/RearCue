package com.rearcue.poc.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MirrorTextSizeTest {

    @Test
    fun `字号默认与历史值读取都归一为小`() {
        assertEquals(MirrorTextSize.SMALL, MirrorTextSize.DEFAULT)
        assertEquals(MirrorTextSize.SMALL, MirrorTextSize.fromName("SMALL"))
        assertEquals(MirrorTextSize.SMALL, MirrorTextSize.fromName("MEDIUM"))
        assertEquals(MirrorTextSize.SMALL, MirrorTextSize.fromName("LARGE"))
        assertEquals(MirrorTextSize.SMALL, MirrorTextSize.fromName(null))
        assertEquals(MirrorTextSize.SMALL, MirrorTextSize.fromName("unknown"))
    }
}
