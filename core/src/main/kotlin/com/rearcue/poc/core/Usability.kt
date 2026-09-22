package com.rearcue.poc.core

/**
 * 可用性引导（票 #28）的词汇与降级判定：纯 Kotlin，JVM 单测钉死。
 *
 * 检测口径见 docs/poc-findings.md「票 #27 验收」：`appops get <pkg>` 的 `MIUIOP(10008)` 与
 * `MIUIOP(10053)` **双 allow ⇔ MIUI「自启动」白名单在册**（设置页开关翻转时两 op 同步翻）；
 * 两 op 可被外部 `appops set` 人为写岔，故不一致只能报「状态存疑」，不得报健康。
 */

/** 单个 MIUI appop 的模式读数（Android 层把 AppOpsManager int 模式折算成此词汇后再判定）。 */
enum class AppOpMode { ALLOWED, IGNORED, OTHER }

/** 自启动放行状态（MIUI「自启动」白名单在册与否的判定结果）。 */
enum class AutostartState {

    /** 两 op 双 allow ⇔ 白名单在册。 */
    GRANTED,

    /** 两 op 双 ignore：未检测到自启动放行。 */
    DENIED,

    /**
     * 状态存疑：app 内读数拿不到、两 op 写岔、allow/ignore 之外的第三态（default/ask 等）——
     * 按 findings「降级判定」降为「仅手动跳转 + 明示文案」，**绝不报健康**。
     */
    IN_DOUBT,
}

/** 降级判定（findings 票 #27「降级判定」的纯函数落地）：两 op 读数 → [AutostartState]。 */
object AutostartJudge {

    /** null = 该 op 读数不可得（拿不到/异常），按「状态存疑」降级。 */
    fun judge(op10008: AppOpMode?, op10053: AppOpMode?): AutostartState = when {
        op10008 == null || op10053 == null -> AutostartState.IN_DOUBT
        op10008 == AppOpMode.ALLOWED && op10053 == AppOpMode.ALLOWED -> AutostartState.GRANTED
        op10008 == AppOpMode.IGNORED && op10053 == AppOpMode.IGNORED -> AutostartState.DENIED
        else -> AutostartState.IN_DOUBT
    }
}

/** 可用性引导横幅的触发原因（横幅文案按原因渲染；`AUTOSTART_IN_DOUBT` 即降级形态）。 */
enum class UsabilityReason {

    /** 未检测到自启动放行（两 op 双 ignore）。 */
    AUTOSTART_DENIED,

    /** 自启动状态存疑（降级形态：仅手动跳转 + 明示文案，绝不显示「健康」）。 */
    AUTOSTART_IN_DOUBT,

    /** 通知监听不健康（监听服务未连接）。 */
    LISTENER_UNHEALTHY,
}
