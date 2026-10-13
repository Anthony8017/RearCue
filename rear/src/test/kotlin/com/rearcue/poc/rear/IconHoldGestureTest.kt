package com.rearcue.poc.rear

import com.rearcue.poc.rear.IconHoldSession.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals

class IconHoldGestureTest {
    private val bounds = IconHoldGesture.Bounds(0f, 0f, 100f, 100f)

    @Test
    fun `半秒前松开是短按`() {
        val session = IconHoldSession()
        session.down("wechat", 0L, 40f, 40f, slop = 8f)
        assertEquals(Outcome.None, session.tick(179L))
        assertEquals(Outcome.Tap("wechat"), session.up(179L, 42f, 40f, bounds))
    }

    @Test
    fun `先略缩小再在半秒提交且松手不再点按`() {
        val session = IconHoldSession()
        session.down("wechat", 0L, 40f, 40f, slop = 8f)
        assertEquals(Outcome.Preview("wechat"), session.tick(180L))
        assertEquals(Outcome.Commit("wechat"), session.tick(500L))
        assertEquals(Outcome.None, session.up(700L, 180f, 40f, bounds))
    }

    @Test
    fun `手指移出图标才取消且不再点按`() {
        val session = IconHoldSession()
        session.down("wechat", 0L, 40f, 40f, slop = 8f)
        assertEquals(Outcome.None, session.move(70f, 40f, bounds))
        assertEquals(Outcome.Cancel, session.move(160f, 40f, bounds))
        assertEquals(Outcome.None, session.up(200L, 160f, 40f, bounds))
    }

    @Test
    fun `手指没动时图标挪走不取消`() {
        val session = IconHoldSession()
        session.down("wechat", 0L, 40f, 40f, slop = 8f)
        val movedAway = IconHoldGesture.Bounds(200f, 200f, 300f, 300f)
        assertEquals(Outcome.None, session.move(44f, 40f, movedAway))
        assertEquals(Outcome.Tap("wechat"), session.up(200L, 44f, 40f, movedAway))
    }

    @Test
    fun `半秒前页面换走会取消半秒后不会`() {
        val early = IconHoldSession()
        early.down("wechat", 0L, 40f, 40f, slop = 8f)
        early.tick(180L)
        assertEquals(Outcome.Cancel, early.abandon())

        val late = IconHoldSession()
        late.down("wechat", 0L, 40f, 40f, slop = 8f)
        assertEquals(Outcome.Commit("wechat"), late.tick(500L))
        assertEquals(Outcome.None, late.abandon())
    }
}
