package com.rearcue.poc.agentmirror

/** Agent 镜像链路状态（spec 0010 / 票 #81）：主屏 Agent 区状态行的展示面。 */
enum class AgentLinkStatus {
    /** 无配对凭据：粘贴桌面端链接完成配对。 */
    UNPAIRED,

    /** 首连进行中。 */
    CONNECTING,

    /** 认证通过（pair_status=matched）。 */
    CONNECTED,

    /** 断线退避重连中（spec 0010：零打扰，背屏已回落）。 */
    RECONNECTING,

    /** 不可重试停机（AUTH_FAILED/4011/4013 等）：需重新配对。 */
    DISCONNECTED,

    /** 总开关关闭。 */
    DISABLED,
}
