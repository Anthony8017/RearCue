/**
 * 来源能力表（spec 0018-2 / 票 #172 雏形 → spec 0018-4 票 #174 按通道实测声明 →
 * spec 0018-6 票 #176 dsh 批准应答）：**纯模块、零副作用**（判例可直引）。
 * 桥对每来源声明能力词，随 /snapshot 下发。词表：`waiting`＝等待语义可用；
 * `approve`＝可远程批准应答。没写进表的能力＝不可用（缺省保守，批准入口不开）。
 * ZCode（票 #240）经桥声明；交互 server-request 不可见时批准不会产生待决项。
 *
 * approve 实测口径（票 #174 → #176）：
 * - **claude**：PreToolUse 本地批准通道（claude-hook.mjs 查远程批准并回写 allow/deny）——
 *   声明 approve。BRIDGE_APPROVE=off 时通道整体下线、只声明 waiting（不阻塞时也不误报）。
 * - **codex**：无程序化批准通道（notify 只报状态），**不声明** approve（票面允许）。
 * - **dsh**：批准应答走只读插件的 waterfall 应答通道（票 #176）——声明 approve，
 *   且**随插件活性打折**（[capabilitiesFor]）：插件失联即收回 approve，快照收紧、
 *   动作回 unsupported（不悬挂）。
 */
export const SOURCE_CAPABILITIES = {
  codex: ["waiting"],
  claude: process.env.BRIDGE_APPROVE === "off" ? ["waiting"] : ["waiting", "approve"],
  dsh: process.env.BRIDGE_APPROVE === "off" ? ["waiting"] : ["waiting", "approve"],
  // ZCode app-server 的 interaction server-request 可由桥回 approve/reject/select；
  // 桌面私有子进程不可接管时该能力不会产生待决项，但契约数据路径保持兼容。
  zcode: process.env.BRIDGE_APPROVE === "off" ? ["waiting"] : ["waiting", "approve"],
};

/**
 * 生效能力表（导出供判例锁契约）：桥声明表打上插件活性折扣——dsh 的 approve 只在
 * 插件在线时生效（批准应答通道在插件里）。`dshLive=false` ⇒ dsh 只剩 waiting。
 */
export function capabilitiesFor(dshLive) {
  const caps = {
    codex: [...SOURCE_CAPABILITIES.codex],
    claude: [...SOURCE_CAPABILITIES.claude],
    dsh: [...SOURCE_CAPABILITIES.dsh],
    zcode: [...SOURCE_CAPABILITIES.zcode],
  };
  if (!dshLive) caps.dsh = caps.dsh.filter((word) => word !== "approve");
  return caps;
}
