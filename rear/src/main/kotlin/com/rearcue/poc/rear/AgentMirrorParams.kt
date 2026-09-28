package com.rearcue.poc.rear

/**
 * Agent Mirror 渲染参数（spec 0010 / 票 #84，纯函数——JVM 判例沿 [ChargingWater]）：
 * 状态 × 屏幕几何 → 布局参数。渲染层零决策照单执行，几何语义锁死在单测。
 *
 * grilling #113（去状态词、正文最大化）后原状态标语字号档（statusSp）与动作行截断
 * （actionLine）随状态词/动作行一并删除——本对象只剩正文一档。
 */
object AgentMirrorParams {

    /** 会话输出正文的字号档（sp）：镜像的主体阅读面（票 #86 实机修订：正文区独占剩余高度＋内部滚动，
     * 不再用 maxLines 截断——历史经验：maxLines 档在小屏把核心阅读面推出视口）。 */
    const val REPLY_SP_BASE = 16f
}
