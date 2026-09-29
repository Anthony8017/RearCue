#!/usr/bin/env node
/**
 * 注册 Claude Code hooks（ADR 0006 / 票 #119；spec 0017 / 票 #169 加 MessageDisplay）：
 * - `Stop`（回合结束 → idle + 最后一段正文）
 * - `Notification`（permission_prompt|agent_needs_input → waiting，沿既有 matcher 词表）
 * - `MessageDisplay`（**回合进行中**按「新完成的整行」批量吐正文 → 逐批上屏，spec 0017 的
 *   Claude 流式来源；官方说明：display-only，不改 Claude 的存储内容与模型输入）
 *
 * 幂等（已有同 command 即跳过）；写前备份 settings.json.bak-<timestamp>；
 * JSON 坏则拒绝写入（不动用户配置）。
 *
 * **路径自愈**：Command 里写的是本脚本所在目录的绝对路径。桥曾从别的工作树跑起来过，
 * 于是 settings.json 里留下指向那个旧工作树的路径（实测踩到：钩子跑的是过期副本）。
 * 注册时按「同一个 claude-hook.mjs 文件名」认领并**改写成本仓库的当前路径**，不留旧路径。
 *
 * 卸载：register-claude-hooks.mjs --unregister（按 command 反查删除）。
 */
import { homedir } from "node:os";
import { join, basename } from "node:path";
import { readFileSync, writeFileSync, copyFileSync, existsSync } from "node:fs";

const settingsPath = join(homedir(), ".claude", "settings.json");
const hookCmd = `node "${join(import.meta.dirname, "claude-hook.mjs")}"`;
const HOOK_BASENAME = basename(join(import.meta.dirname, "claude-hook.mjs"));

/** 事件名 → 是否我们的钩子（认领判据：command 里提到同名 claude-hook.mjs，路径不管）。 */
const isOurHook = (h) =>
  typeof h?.command === "string" && h.command.includes(HOOK_BASENAME);

function main() {
  const unregister = process.argv.includes("--unregister");
  if (!existsSync(settingsPath)) {
    console.error(`[hooks] ${settingsPath} 不存在，跳过`);
    process.exit(1);
  }
  const raw = readFileSync(settingsPath, "utf8");
  let settings;
  try {
    settings = JSON.parse(raw);
  } catch (e) {
    console.error(`[hooks] settings.json 解析失败，拒绝写入: ${e.message}`);
    process.exit(1);
  }
  const ts = new Date().toISOString().replace(/[:.]/g, "-");
  const before = raw;
  settings.hooks ||= {};

  const EVENTS = ["Stop", "Notification", "MessageDisplay"];

  const stripOurHooks = () => {
    for (const ev of EVENTS) {
      const arr = settings.hooks[ev];
      if (!Array.isArray(arr)) continue;
      settings.hooks[ev] = arr
        .map((m) => ({ ...m, hooks: (m.hooks || []).filter((h) => !isOurHook(h)) }))
        .filter((m) => m.hooks.length > 0);
      if (settings.hooks[ev].length === 0) delete settings.hooks[ev];
    }
  };

  if (unregister) {
    stripOurHooks();
    const afterUnregister = JSON.stringify(settings, null, 2) + "\n";
    if (afterUnregister === before) {
      console.log("[hooks] 本就没有桥钩子，跳过写入");
      return;
    }
    copyFileSync(settingsPath, `${settingsPath}.bak-${ts}`);
    writeFileSync(settingsPath, afterUnregister);
    console.log(`[hooks] 已注销 Stop/Notification/MessageDisplay 桥钩子（备份 ${settingsPath}.bak-${ts}）`);
    return;
  }

  // 先摘掉同名旧条目（可能指向旧工作树），再按当前路径重装——路径自愈。
  stripOurHooks();

  // Stop：回合结束 → 桥 /hooks/claude {hook_event_name:"Stop", last_assistant_message,...}
  settings.hooks.Stop ||= [];
  settings.hooks.Stop.push({ hooks: [{ type: "command", command: hookCmd }] });
  // Notification：沿既有 matcher 词表（permission_prompt|agent_needs_input|elicitation*）
  // ——等待确认的实时信号；与既有 claude-notify.ps1 并列，不替换。
  settings.hooks.Notification ||= [];
  const matcher = "permission_prompt|agent_needs_input|elicitation_dialog|elicitation_url_dialog";
  let entry = settings.hooks.Notification.find((m) => m.matcher === matcher);
  if (!entry) {
    entry = { matcher, hooks: [] };
    settings.hooks.Notification.push(entry);
  }
  entry.hooks.push({ type: "command", command: hookCmd });
  // MessageDisplay（spec 0017 / 票 #169）：回合进行中按「新完成的整行」批量吐正文。
  settings.hooks.MessageDisplay ||= [];
  settings.hooks.MessageDisplay.push({ hooks: [{ type: "command", command: hookCmd }] });

  const after = JSON.stringify(settings, null, 2) + "\n";
  // 幂等短路：写出来的与盘上逐字节相同就不备份不写盘（start.ps1 每次启动都会调，
  // 避免 .bak 文件风暴）。路径自愈场景下（旧路径 → 当前路径）这里必然不同，会正常改写。
  if (after === before) {
    console.log("[hooks] 已是最新，跳过写入");
    return;
  }
  copyFileSync(settingsPath, `${settingsPath}.bak-${ts}`);
  writeFileSync(settingsPath, after);
  console.log(`[hooks] 已注册 Stop + Notification + MessageDisplay 桥钩子（${hookCmd}）`);
}

main();
