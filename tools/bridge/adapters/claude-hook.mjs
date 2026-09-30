#!/usr/bin/env node
/**
 * Claude Code hooks → 桥 转发器（ADR 0006 / 票 #119）：读 stdin 的 hook JSON，
 * 原样 POST 到桥 ` /hooks/claude`，**恒 exit 0**（桥不在也绝不影响会话——hooks 的
 * 红线：转发失败只记 stderr，不阻塞 Stop/Notification 流程）。
 *
 * 注册见 register-claude-hooks.mjs（settings.json 的 Stop + Notification + MessageDisplay
 * ＋ PreToolUse 四个钩子；MessageDisplay 是回合内的逐批正文来源，spec 0017 / 票 #169）。
 *
 * PreToolUse 是**本地批准通道**（spec 0018-4 / 票 #174，ADR 0009）：向桥查远程批准决定
 * （GET /action/pending）——有决定就回写 allow/deny（一次性，取走即清）并回报
 * approval-resolved（等待标记随之消失）；没有决定时**默认零等待直接 ask**（正常工作流
 * 零打扰），仅当桥开批准等待窗（armed＋BRIDGE_APPROVE_WAIT_MS>0）才有限等待手机拍板。
 * 红线不变：通道只承载批准类应答，没有自由文字入口（ADR 0009）。
 */
const PORT = process.env.BRIDGE_PORT || "18787";
const BRIDGE = `http://127.0.0.1:${PORT}`;

async function postHook(bodyText) {
  await fetch(`${BRIDGE}/hooks/claude`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: bodyText,
    signal: AbortSignal.timeout(2000),
  });
}

/** PreToolUse 的批准应答（Claude Code hook 输出契约）：permissionDecision 只有 allow|deny|ask。 */
function decisionOutput(decision, reason) {
  return JSON.stringify({
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: decision,
      permissionDecisionReason: reason,
    },
  });
}

/**
 * PreToolUse 流程：查决定 → 有就应用；没有且桥开窗才等一轮（waitMs 上限自带钳制）。
 * 任何失败都回落「ask」（交回终端正常询问）——批准通道坏掉绝不挡住会话。
 */
async function handlePreToolUse(body) {
  const sessionId = body.session_id || body.sessionId || "";
  const toolName = body.tool_name || body.toolName || "";
  if (!sessionId) return null;
  let pending;
  try {
    const res = await fetch(
      `${BRIDGE}/action/pending?sessionId=${encodeURIComponent(sessionId)}`,
      { signal: AbortSignal.timeout(2000) },
    );
    pending = await res.json();
  } catch {
    return null;
  }
  let entry = pending;
  if (!entry.decision && entry.waitMs > 0) {
    // 等待窗开着：先把「等你确认」报上桥（手机提醒/批准入口靠它），再按半秒节拍取决定。
    try {
      await postHook(
        JSON.stringify({
          hook_event_name: "Notification",
          session_id: sessionId,
          type: "permission_prompt",
          summary: toolName ? `想运行 ${toolName}` : "想执行一个动作",
        }),
      );
    } catch {
      /* 报不上不影响取决定 */
    }
    const deadline = Date.now() + Math.min(Number(entry.waitMs) || 0, 60_000);
    while (!entry.decision && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 500));
      try {
        const res = await fetch(
          `${BRIDGE}/action/pending?sessionId=${encodeURIComponent(sessionId)}`,
          { signal: AbortSignal.timeout(2000) },
        );
        entry = await res.json();
      } catch {
        break;
      }
    }
  }
  if (!entry.decision) return null; // 超时/没决定：回落 ask（终端照常询问）
  try {
    await postHook(JSON.stringify({ type: "approval-resolved", session_id: sessionId }));
  } catch {
    /* 回报失败不影响决定落地 */
  }
  return decisionOutput(
    entry.decision === "deny" ? "deny" : "allow",
    `remote approval via RearCue（requestId=${entry.requestId || "-"}）`,
  );
}

let raw = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (c) => (raw += c));
process.stdin.on("end", async () => {
  try {
    const body = raw.trim() || "{}";
    const parsed = JSON.parse(body); // 只转发合法 JSON（坏 stdin 直接放弃）
    const event = parsed.hook_event_name || parsed.type;
    // PreToolUse 走批准通道（先决策后转发）；其余事件照旧 fire-and-forget。
    if (event === "PreToolUse") {
      const output = await handlePreToolUse(parsed);
      await postHook(body).catch(() => {});
      if (output) process.stdout.write(output);
      process.exit(0);
    }
    await postHook(body);
  } catch {
    /* 桥未启动/超时：静默（fire-and-forget，绝不阻塞会话） */
  }
  process.exit(0);
});
