// 问答流构造测试（spec 0017 / 票 #169）：node --test tools/bridge/adapters/turn-log.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { createTurnLog, isInjectedUserText } from "./turn-log.mjs";

test("提问与回答按时间顺序同流", () => {
  const log = createTurnLog();
  log.user("帮我把背屏改成靠左对齐");
  log.agent("好，我先改版心。");
  assert.deepEqual(
    log.list().map((t) => [t.role, t.text]),
    [
      ["user", "帮我把背屏改成靠左对齐"],
      ["assistant", "好，我先改版心。"],
    ],
  );
});

test("空文与纯空白不产生条目", () => {
  const log = createTurnLog();
  log.user("   ");
  log.agent("");
  log.delta("");
  assert.equal(log.size, 0);
  assert.deepEqual(log.list(), []);
});

test("同角色同文复读不新增条目，但角色不同要各记一条", () => {
  const log = createTurnLog();
  log.agent("完成");
  log.agent("完成");
  assert.equal(log.size, 1);
  // 机主提问与 agent 输出可能同文，不能互相吞。
  log.user("完成");
  assert.equal(log.size, 2);
  assert.deepEqual(log.list().map((t) => t.role), ["assistant", "user"]);
});

test("增量追加到末尾开放条上而不是新开一条", () => {
  const log = createTurnLog();
  log.delta("第一行\n");
  log.delta("第二行\n");
  log.delta("第三行");
  assert.equal(log.size, 1);
  assert.equal(log.list()[0].text, "第一行\n第二行\n第三行");
  assert.equal(log.list()[0].open, true);
});

test("末尾不是开放条时增量新开一条", () => {
  const log = createTurnLog();
  log.user("问一句");
  log.delta("答一句");
  assert.equal(log.size, 2);
  assert.equal(log.list()[1].open, true);
});

test("完整助手文本收口开放条——同一段不出现两次", () => {
  const log = createTurnLog();
  log.delta("前半句");
  // transcript 落盘后拿到的完整文本：以完整文本为准收口（丢掉中间批攒出的近似值）。
  log.agent("前半句，还有后半句。");
  assert.equal(log.size, 1);
  assert.equal(log.list()[0].text, "前半句，还有后半句。");
  assert.equal(log.list()[0].open, undefined);
});

test("开放条收口后再来完整文本就是新一条", () => {
  const log = createTurnLog();
  log.delta("第一次回答");
  log.agent("第一次回答");
  log.agent("第二次回答");
  assert.equal(log.size, 2);
  assert.deepEqual(log.list().map((t) => t.text), ["第一次回答", "第二次回答"]);
});

test("尾部窗口按条数裁剪，从最旧丢", () => {
  const log = createTurnLog({ maxEntries: 3, maxChars: 10000 });
  for (let i = 1; i <= 5; i++) log.agent(`第 ${i} 条`);
  assert.deepEqual(log.list().map((t) => t.text), ["第 3 条", "第 4 条", "第 5 条"]);
});

test("尾部窗口按总字符裁剪，且至少留一条", () => {
  // 窗口量的是「用户实际看到的那段文本」：正文之和 + 条目间分隔（本文件默认分隔 14 字符）。
  const log = createTurnLog({ maxEntries: 50, maxChars: 20 });
  log.user("1234567890");
  log.agent("abcdefghij");
  log.agent("最后的回答");
  const texts = log.list().map((t) => t.text);
  assert.deepEqual(texts, ["最后的回答"]);
  // 两条放得下就不丢：10 + 14 + 5 = 29 ≤ 40。
  const roomy = createTurnLog({ maxEntries: 50, maxChars: 40 });
  roomy.user("1234567890");
  roomy.agent("最后的回答");
  assert.equal(roomy.size, 2);
  // 单条超上限也不清空（至少留一条），否则屏上会突然全空。
  const single = createTurnLog({ maxEntries: 50, maxChars: 4 });
  single.agent("这一条很长很长很长");
  assert.equal(single.size, 1);
});

test("latestReply 取末尾最后一条助手输出（跳过提问）", () => {
  const log = createTurnLog();
  log.user("问");
  assert.equal(log.latestReply(), null, "只有提问时没有助手输出可为旧字段兜底");
  log.agent("答一");
  log.user("再问");
  assert.equal(log.latestReply(), "答一");
  log.delta("答二（半截）");
  assert.equal(log.latestReply(), "答二（半截）", "开放条也算当前可见输出");
});

test("reset 清空（会话切换不跨会话带旧流）", () => {
  const log = createTurnLog();
  log.user("问");
  log.agent("答");
  log.reset();
  assert.equal(log.size, 0);
  assert.equal(log.latestReply(), null);
});

test("list 返回副本——外部改动不影响内部状态", () => {
  const log = createTurnLog();
  log.user("问");
  const snapshot = log.list();
  snapshot[0].text = "被改了";
  snapshot.push({ role: "user", text: "塞进来的" });
  assert.equal(log.list().length, 1);
  assert.equal(log.list()[0].text, "问");
});

// ---- 完整历史（票 #177）：窗口是视图，不删存量 ----

test("全量历史：窗口截掉的更早条目在 all() 里还在（票 #177）", () => {
  const log = createTurnLog({ maxEntries: 2, maxChars: 16000 });
  log.user("第 1 问");
  log.agent("第 1 答");
  log.user("第 2 问");
  log.agent("第 2 答");
  log.user("第 3 问");
  assert.deepEqual(log.list().map((t) => t.text), ["第 2 答", "第 3 问"], "实时推流口径仍是窗口");
  assert.equal(log.size, 2, "size 口径＝窗口条数，与 list() 一致");
  assert.equal(log.all().length, 5, "全量 5 条");
  assert.equal(log.all()[0].text, "第 1 问");
});

test("全量历史：增量不吞更早条目，reset 才清（票 #177）", () => {
  const log = createTurnLog({ maxEntries: 1 });
  log.user("问");
  log.agent("答");
  log.delta("续"); // 收口后的增量新开一条（既有口径），全量即 3 条
  assert.equal(log.list().length, 1, "窗口只剩开放条");
  assert.equal(log.all().length, 3);
  assert.equal(log.all()[0].text, "问");
  log.reset();
  assert.equal(log.all().length, 0);
});

test("全量历史：内存护栏超上限丢最旧（票 #177）", () => {
  const log = createTurnLog({ maxEntries: 1, maxHistory: 3 });
  for (let i = 1; i <= 5; i++) log.user(`第 ${i} 问`);
  assert.deepEqual(log.all().map((t) => t.text), ["第 3 问", "第 4 问", "第 5 问"]);
});

test("all 返回副本——与 list 同规矩（票 #177）", () => {
  const log = createTurnLog();
  log.user("问");
  const snapshot = log.all();
  snapshot[0].text = "被改了";
  assert.equal(log.all()[0].text, "问");
});

// ---- 电脑端不可见的注入上下文（票 #248）----

test("可识别的 Codex 注入上下文不进入提问流", () => {
  const log = createTurnLog();
  log.user([
    "# AGENTS.md instructions for C:\\work\\repo",
    "",
    "<INSTRUCTIONS>",
    "hidden rules",
    "</INSTRUCTIONS>",
  ].join("\n"));
  log.user('<external_codex_apps_open_page>{"page_id":null}</external_codex_apps_open_page>');
  log.user('<subagent_notification>{"agent_path":"a","status":"completed"}</subagent_notification>');
  log.user("<system-reminder>\nhidden reminder\n</system-reminder>");
  log.user("真实提问");
  assert.deepEqual(log.list().map((t) => t.text), ["真实提问"]);
  assert.deepEqual(log.all().map((t) => t.text), ["真实提问"]);
});

test("无法确认结构的相似文本仍按真实提问保留", () => {
  assert.equal(isInjectedUserText("<subagent_notification>我手工写了这几个字</subagent_notification>"), false);
  assert.equal(isInjectedUserText("# AGENTS.md instructions for 我的普通问题"), false);
  const log = createTurnLog();
  log.user("<subagent_notification>我手工写了这几个字</subagent_notification>");
  assert.equal(log.all().length, 1);
});

test("全信息条目保留思考、工具摘要与可展开原文", () => {
  const log = createTurnLog();
  log.thinking("先确认边界");
  log.tool("读取 CONTEXT.md", "完整文件内容", { toolName: "read", path: "CONTEXT.md" });
  log.toolResult("读取成功", "file-output");
  log.error("命令失败", "stack-trace");
  const rows = log.list();
  assert.deepEqual(rows.map((row) => row.kind), ["thinking", "tool", "tool_result", "error"]);
  assert.equal(rows[1].detail, "完整文件内容");
  assert.equal(rows[1].toolName, "read");
  assert.equal(rows[2].detail, "file-output");
  assert.equal(rows[3].detail, "stack-trace");
  assert.ok(rows.every((row) => row.entryId));
});

test("思考增量与回答增量互不串条，complete 只收口末尾开放条", () => {
  const log = createTurnLog();
  log.thinkingDelta("先想");
  log.thinkingDelta("清楚");
  log.complete();
  log.delta("正式回答");
  log.complete();
  const rows = log.list();
  assert.deepEqual(rows.map((row) => [row.kind, row.text, Boolean(row.open)]), [
    ["thinking", "先想清楚", false],
    ["answer", "正式回答", false],
  ]);
});
