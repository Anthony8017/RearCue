#!/usr/bin/env node
/**
 * Claude Code hooks → 桥 转发器（ADR 0006 / 票 #119）：读 stdin 的 hook JSON，
 * 原样 POST 到桥 ` /hooks/claude`，**恒 exit 0**（桥不在也绝不影响会话——hooks 的
 * 红线：转发失败只记 stderr，不阻塞 Stop/Notification 流程）。
 *
 * 注册见 register-claude-hooks.mjs（settings.json 的 Stop + Notification + MessageDisplay
 * 三个钩子；MessageDisplay 是回合内的逐批正文来源，spec 0017 / 票 #169）。
 */
const PORT = process.env.BRIDGE_PORT || "18787";

let raw = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (c) => (raw += c));
process.stdin.on("end", async () => {
  try {
    const body = raw.trim() || "{}";
    JSON.parse(body); // 只转发合法 JSON（坏 stdin 直接放弃）
    await fetch(`http://127.0.0.1:${PORT}/hooks/claude`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
      signal: AbortSignal.timeout(2000),
    });
  } catch {
    /* 桥未启动/超时：静默（fire-and-forget，绝不阻塞会话） */
  }
  process.exit(0);
});
