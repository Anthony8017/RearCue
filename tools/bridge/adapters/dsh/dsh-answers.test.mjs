/**
 * 批准/提问应答纯函数判例（票 #176，形状按真机重写 票 #181）。
 * 红线面：应答只可能是批准 waterfall 的规范词（`allowed-once` / `rejected`）或提问的
 * `{answers:[{id, selected:[label]}]}`；**自由文字（custom）在本模块根本不存在**——
 * 穷举断言锁死「动作词 → 应答值」的映射边界，认不出一律 null（调用方委派 next()）。
 * 跑法：node --test tools/bridge/adapters/dsh/dsh-answers.test.mjs
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { actionFromEntry, APPROVAL_OUTCOMES, approvalOutcomeFor, questionAnswerFor } from "./dsh-answers.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const SOURCE = readFileSync(join(HERE, "dsh-answers.mjs"), "utf8");

const ask = {
  question: "走哪条路？",
  questionId: "q-1",
  options: [
    { id: "甲案", label: "甲案" },
    { id: "乙案", label: "乙案" },
  ],
  multiSelect: false,
  planApproveLabel: null,
};

test("批准应答：只可能落在规范词表里（allowed-once 是唯一同意）", () => {
  assert.equal(approvalOutcomeFor("approve"), "allowed-once");
  assert.equal(approvalOutcomeFor("reject"), "rejected");
  assert.equal(approvalOutcomeFor("select"), null, "点选对批准无意义 → 委派");
  assert.equal(approvalOutcomeFor("nonsense"), null);
  assert.equal(approvalOutcomeFor(null), null);
  for (const action of ["approve", "reject", "select", "x"]) {
    const value = approvalOutcomeFor(action);
    assert.ok(value === null || APPROVAL_OUTCOMES.includes(value), `应答必须在规范词表里：${value}`);
  }
});

test("提问应答：selected 装的是选项 label（真机选项没有 id）", () => {
  assert.deepEqual(questionAnswerFor("select", "乙案", ask), { answers: [{ id: "q-1", selected: ["乙案"] }] });
  assert.equal(questionAnswerFor("select", "不存在", ask), null, "不在选项表里的 label 不采信");
  assert.equal(questionAnswerFor("select", null, ask), null);
  assert.equal(questionAnswerFor("reject", null, ask), null, "提问没有「拒绝」应答形态 → 委派");
});

test("提问应答：approve 走 intent.approve 的 label，缺省第一个选项", () => {
  assert.deepEqual(questionAnswerFor("approve", null, { ...ask, planApproveLabel: "乙案" }), {
    answers: [{ id: "q-1", selected: ["乙案"] }],
  });
  assert.deepEqual(questionAnswerFor("approve", null, ask), { answers: [{ id: "q-1", selected: ["甲案"] }] });
  assert.equal(questionAnswerFor("approve", null, { ...ask, options: [] }), null);
});

test("提问应答里不存在自由文字字段（红线：custom 永不出现）", () => {
  const answers = [
    questionAnswerFor("select", "甲案", ask),
    questionAnswerFor("approve", null, ask),
  ];
  for (const answer of answers) {
    assert.deepEqual(Object.keys(answer), ["answers"]);
    assert.deepEqual(Object.keys(answer.answers[0]).sort(), ["id", "selected"]);
  }
  assert.equal(/custom/.test(SOURCE), true, "注释里应说明红线（custom 不存在）");
  assert.equal(/custom\s*:/.test(SOURCE.replace(/\/\/.*$/gm, "")), false, "代码里不得出现 custom 赋值");
});

test("桥决定条目 → 动作词：optionId 优先（选择题），allow/deny 映射批准", () => {
  assert.deepEqual(actionFromEntry({ decision: "allow" }), { action: "approve", optionId: null });
  assert.deepEqual(actionFromEntry({ decision: "deny" }), { action: "reject", optionId: null });
  assert.deepEqual(actionFromEntry({ decision: "allow", optionId: "甲案" }), { action: "select", optionId: "甲案" });
  assert.deepEqual(actionFromEntry({}), { action: null, optionId: null });
  assert.deepEqual(actionFromEntry(null), { action: null, optionId: null });
});
