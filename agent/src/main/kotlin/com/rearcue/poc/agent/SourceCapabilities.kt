package com.rearcue.poc.agent

/**
 * 来源能力表（spec 0018-2 / 票 #172 雏形，spec 0018-4 批准入门消费）：每来源声明能力词。
 *
 * 能力词表：`waiting`＝该来源的等待语义可用（Waiting-for-Approval 插队/标记走它）；
 * `approve`＝可远程批准应答（票 #174 起按通道实测声明）。
 * **没声明的能力一律当作不可用**（缺省保守：批准入口不开）。
 *
 * 声明来源：桥（`GET /snapshot` 的 `capabilities`）声明桥来源；ZCode 直连不经桥，
 * 由 [DEFAULTS] 内置。旧桥没发能力表时按 [DEFAULTS] 照常工作（能力表是增量声明，不挡镜像）。
 */
data class SourceCapabilities(val bySource: Map<String, Set<String>>) {

    /** 该来源是否声明了某能力；来源不认识 / 能力没声明 → false（缺省保守）。 */
    fun can(source: String?, capability: String): Boolean {
        val key = source?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return capability in (bySource[key] ?: return false)
    }

    companion object {

        /** 等待语义可用（四来源皆有此语义；桥声明可覆盖/收紧）。 */
        const val WAITING = "waiting"

        /** 远程批准可应答（票 #174 留位；未声明即不可批准）。 */
        const val APPROVE = "approve"

        /**
         * 内置默认表：四来源的等待语义都可用（产品口径），批准一概未声明。
         * 桥声明经 [merge] 叠加其上——同名来源以桥声明为准（桥说没有就是没有）。
         */
        val DEFAULTS = SourceCapabilities(
            mapOf(
                AgentSources.ZCODE to setOf(WAITING),
                AgentSources.CODEX to setOf(WAITING),
                AgentSources.CLAUDE to setOf(WAITING),
                AgentSources.DSH to setOf(WAITING),
            ),
        )

        /** 合并：[declared] 覆盖同名来源的整组能力词，未提及的来源保留 [base] 声明。 */
        fun merge(base: SourceCapabilities, declared: SourceCapabilities): SourceCapabilities =
            SourceCapabilities(base.bySource + declared.bySource)
    }
}
