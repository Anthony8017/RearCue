package com.rearcue.poc.rear

/**
 * 会话选择器的渲染参数（spec 0020 / 票 #204 定密排；票 #211 二次修订废行数上限；issue #213 改两行式）：
 * 沿 [AgentMirrorParams] / [IconGrid] 的参数对象惯例。平铺后「列表多高」不再是参数换算——
 * 高度上限即 [SafeArea.flushReadingViewport] 的可用高，由渲染层直接取用（[AgentPickerLayer]），
 * 本对象只剩两行条目高度常量。
 *
 * 沿革：spec 0016 原判「一屏约 4 行、点列表外关闭」→ 票 #160 改「3 行＋可见底部关闭带」（票 #162
 * 评审锁定带的最小高度）→ 机主 2026-09-30 推翻关闭带、定密排（spec 0020 / 票 #204）→ 同日机主
 * 看屏二次定夺（票 #211）：**推翻行数上限，列表平铺**——高度上限＝
 * [SafeArea.flushReadingViewport] 可用高、行数无上限，会话多时末行可被屏缘截半行（可点，点即选中）、
 * 其余列表内滚动；会话少时列表自然矮、顶对齐，下方留黑＝点外关闭区的一部分。
 *
 * issue #213：条目改「主行 + 副行」，行高从单行 28dp 提到两行 44dp——**只改内容高度**，
 * 平铺、滚动、截半行、点外关闭与等待/选中标记几何均不变；单屏可容纳行数随之减半是预期代价。
 * 44dp 仍是 picker 专属行高，**不挂** [com.rearcue.poc.design.RearCueTouch] 的 48dp 触控目标。
 */
object AgentPickerParams {

    /** 两行条目最小高度（dp）：picker 专属，不是触控目标（见对象 KDoc）。 */
    const val ROW_HEIGHT_DP = 44
}
