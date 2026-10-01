/**
 * ZCode model-io 只读完整历史（review 2026-10-01）。
 *
 * `GET /history` 需要能按需从 model-io JSONL 重建当前会话更早的机主提问/agent 输出，
 * 不能只拿桥进程内的 20 条实时窗口或 2 MiB 尾部。这里只读本地 JSONL：
 * 不 resume 会话、不调 app-server、不碰 remote relay。
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";

function nonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function finiteTime(value) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const parsed = Date.parse(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return 0;
}

function textFromContent(content) {
  if (typeof content === "string") return content.trim();
  if (Array.isArray(content)) {
    return content
      .map((part) => {
        if (typeof part === "string") return part;
        if (typeof part?.text === "string") return part.text;
        if (typeof part?.content === "string") return part.content;
        return "";
      })
      .join("")
      .trim();
  }
  return "";
}

function isDisplayableUserText(text) {
  const value = nonEmptyString(text);
  if (!value) return false;
  return !(
    /^<system-reminder>[\s\S]*<\/system-reminder>$/i.test(value) ||
    /^<environment_details>/i.test(value) ||
    /^Caveat:/i.test(value)
  );
}

function lastDisplayableUserText(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role !== "user") continue;
    const text = textFromContent(messages[i].content);
    if (isDisplayableUserText(text)) return text;
  }
  return null;
}

/**
 * 把按时间顺序的 model-io 完成记录重建成问答流。
 *
 * model-io 每条记录的 `request.messages` 是随上下文窗口滚动的输入快照，直接展开会把
 * 旧问答重复几十遍。这里的稳健口径与实时尾部映射一致：每个 turnId 的每个机主原文只记一次，
 * 每条完成响应只记一次 assistant 原文；同一 turnId 的多次模型调用（工具循环）保留各自的
 * 可见 assistant 输出，但不重复插入同一提问。
 */
export function reconstructZCodeHistory(records) {
  const turns = [];
  const seenUsers = new Set();
  const seenResponses = new Set();
  for (const record of records || []) {
    if (!record || typeof record !== "object") continue;
    const messages = Array.isArray(record.request?.messages) ? record.request.messages : [];
    const userText = lastDisplayableUserText(messages);
    const turnId = nonEmptyString(record.turnId) || nonEmptyString(record.requestId) || "default";
    const startedAt = finiteTime(record.startedAt || record.completedAt);
    const completedAt = finiteTime(record.completedAt || record.startedAt);
    if (userText) {
      const key = `${turnId}\u0000${userText}`;
      if (!seenUsers.has(key)) {
        seenUsers.add(key);
        turns.push({ role: "user", text: userText, ts: startedAt });
      }
    }
    const assistantText = nonEmptyString(record.response?.text);
    if (assistantText) {
      const recordId = nonEmptyString(record.requestId) || `${turnId}\u0000${record.attempt ?? ""}`;
      const key = `${recordId}\u0000${assistantText}`;
      if (!seenResponses.has(key)) {
        seenResponses.add(key);
        turns.push({ role: "assistant", text: assistantText, ts: completedAt });
      }
    }
  }
  return turns;
}

/** 按需读取一个 ZCode 会话的 model-io JSONL；坏行/末尾半行跳过，不污染历史。 */
export function readZCodeHistory(modelIoRoot, sessionId) {
  const safeSessionId = nonEmptyString(sessionId);
  if (!safeSessionId || /[\\/]/.test(safeSessionId)) return [];
  let text;
  try {
    text = readFileSync(join(modelIoRoot, `model-io-${safeSessionId}.jsonl`), "utf8");
  } catch {
    return [];
  }
  const records = [];
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue;
    try {
      records.push(JSON.parse(line));
    } catch {
      /* 只跳过坏 JSONL 行；完整历史仍从其余完成记录重建 */
    }
  }
  return reconstructZCodeHistory(records);
}
