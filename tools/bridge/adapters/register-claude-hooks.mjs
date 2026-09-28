#!/usr/bin/env node
/**
 * 注册 Claude Code hooks（ADR 0006 / 票 #119）：把 Stop（回合结束 → idle）与
 * Notification（permission_prompt|agent_needs_input → waiting，沿既有 matcher 词表）
 * 两个钩子合并进 `~/.claude/settings.json`。幂等（已有同 command 即跳过）；
 * 写前备份 settings.json.bak-<timestamp>；JSON 坏则拒绝写入（不动用户配置）。
 *
 * 卸载：register-claude-hooks.mjs --unregister（按 command 反查删除）。
 */
import { homedir } from "node:os";
import { join } from "node:path";
import { readFileSync, writeFileSync, copyFileSync, existsSync } from "node:fs";

const settingsPath = join(homedir(), ".claude", "settings.json");
const hookCmd = `node "${join(import.meta.dirname, "claude-hook.mjs")}"`;

function main() {
  const unregister = process.argv.includes("--unregister");
  if (!existsSync(settingsPath)) {
    console.error(`[hooks] ${settingsPath} 不存在，跳过`);
    process.exit(1);
  }
  let settings;
  try {
    settings = JSON.parse(readFileSync(settingsPath, "utf8"));
  } catch (e) {
    console.error(`[hooks] settings.json 解析失败，拒绝写入: ${e.message}`);
    process.exit(1);
  }
  const ts = new Date().toISOString().replace(/[:.]/g, "-");
  settings.hooks ||= {};

  const hasCmd = (entries) =>
    (entries || []).some((m) => (m.hooks || []).some((h) => h.command === hookCmd));

  if (unregister) {
    copyFileSync(settingsPath, `${settingsPath}.bak-${ts}`);
    for (const ev of ["Stop", "Notification"]) {
      const arr = settings.hooks[ev];
      if (!Array.isArray(arr)) continue;
      settings.hooks[ev] = arr
        .map((m) => ({ ...m, hooks: (m.hooks || []).filter((h) => h.command !== hookCmd) }))
        .filter((m) => m.hooks.length > 0);
      if (settings.hooks[ev].length === 0) delete settings.hooks[ev];
    }
    writeFileSync(settingsPath, JSON.stringify(settings, null, 2) + "\n");
    console.log(`[hooks] 已注销 Stop/Notification 桥钩子（备份 ${settingsPath}.bak-${ts}）`);
    return;
  }

  const before = JSON.stringify(settings, null, 2) + "\n";

  // Stop：回合结束 → 桥 /hooks/claude {hook_event_name:"Stop", last_assistant_message,...}
  settings.hooks.Stop ||= [];
  if (!hasCmd(settings.hooks.Stop)) {
    settings.hooks.Stop.push({ hooks: [{ type: "command", command: hookCmd }] });
  }
  // Notification：沿既有 matcher 词表（permission_prompt|agent_needs_input|elicitation*）
  // ——等待确认的实时信号；与既有 claude-notify.ps1 并列，不替换。
  settings.hooks.Notification ||= [];
  const matcher = "permission_prompt|agent_needs_input|elicitation_dialog|elicitation_url_dialog";
  let entry = settings.hooks.Notification.find((m) => m.matcher === matcher);
  if (!entry) {
    entry = { matcher, hooks: [] };
    settings.hooks.Notification.push(entry);
  }
  if (!entry.hooks.some((h) => h.command === hookCmd)) {
    entry.hooks.push({ type: "command", command: hookCmd });
  }

  const after = JSON.stringify(settings, null, 2) + "\n";
  // 幂等短路：无变化不备份不写盘（start.ps1 每次启动都会调，避免 .bak 文件风暴）。
  if (before === after) {
    console.log("[hooks] 已是最新，跳过写入");
    return;
  }
  copyFileSync(settingsPath, `${settingsPath}.bak-${ts}`);
  writeFileSync(settingsPath, after);
  console.log(`[hooks] 已注册 Stop + Notification 桥钩子（备份 ${settingsPath}.bak-${ts}）`);
}

main();
