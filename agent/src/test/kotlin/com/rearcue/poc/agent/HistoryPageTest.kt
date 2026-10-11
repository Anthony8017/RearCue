package com.rearcue.poc.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryPageTest {
    @Test fun `paged history preserves continuation and rejects incomplete storage`() {
        val page = BridgeEventCodec.parseHistoryPage("""{"turns":[{"role":"user","text":"old prompt","ts":1}],"nextOffset":1,"total":200,"complete":true}""")!!
        assertEquals(1, page.nextOffset)
        assertEquals(200, page.total)
        assertEquals("old prompt", page.turns.single().text)
        assertNull(BridgeEventCodec.parseHistoryPage("""{"turns":[],"complete":false,"error":"missing file"}"""))
    }
    @Test fun `legacy bridge history remains readable`() {
        val page = BridgeEventCodec.parseHistoryPage("""{"turns":[]}""")!!
        assertNull(page.nextOffset)
        assertEquals(0, page.total)
    }
}
