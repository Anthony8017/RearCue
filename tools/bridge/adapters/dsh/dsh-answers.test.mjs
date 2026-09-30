// DSH 批准类应答纯函数判例（票 #176）：node --test tools/bridge/adapters/dsh/dsh-answers.test.mjs
// 红线面：应答恰好两种形状（{decision:"approve"|"reject"} / {optionId}），
// **自由文字输入在应答形态里根本不存在**（ADR 0009）——穷举断言锁死。
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  answerVia,
  dshAnswerFor,
  questionOptionIdsOf,
  questionOptionsOf,
  sanitizeOptions,
  waterfallResponderOf,
} from "./dsh-answers.mjs";

// ---- 应答值（红线判例） ----

test("approval：approve/reject 出规范应答；select/未知词不答", () => {
  assert.deepEqual(dshAnswerFor("approval", "approve"), { decision: "approve" });
  assert.deepEqual(dshAnswerFor("approval", "reject"), { decision: "reject" });
  assert.equal(dshAnswerFor("approval", "select", "opt-1"), null);
  assert.equal(dshAnswerFor("approval", "free-text"), null);
  assert.equal(dshAnswerFor("approval", undefined), null);
});

test("question：只认 select＋在表选项；approve/reject/表外 id 不答", () => {
  const ids = ["a", "b"];
  assert.deepEqual(dshAnswerFor("question", "select", "b", ids), { optionId: "b" });
  assert.equal(dshAnswerFor("question", "select", "c", ids), null, "表外 id 不造");
  assert.equal(dshAnswerFor("question", "select", null, ids), null);
  assert.equal(dshAnswerFor("question", "approve", "a", ids), null);
  assert.equal(dshAnswerFor("question", "reject", "a", ids), null);
  // 选项表拿不到（载荷没给）时不拦：透传 id（手机侧选项来自同一载荷，来源一致）
  assert.deepEqual(dshAnswerFor("question", "select", "x", []), { optionId: "x" });
  assert.deepEqual(dshAnswerFor("question", "select", "x", undefined), { optionId: "x" });
});

test("红线：穷举所有动作词，应答形状只有两种且无任何自由文字面", () => {
  const actions = ["approve", "reject", "select", "say", "prompt", "type", "", undefined, null, { text: "hi" }];
  const shapes = new Set();
  for (const kind of ["approval", "question", "other", null]) {
    for (const action of actions) {
      const answer = dshAnswerFor(kind, action, "opt", ["opt"]);
      if (!answer) continue;
      const keys = Object.keys(answer).sort().join(",");
      shapes.add(keys);
      assert.ok(
        keys === "decision" || keys === "optionId",
        `出现第三种应答形状：${JSON.stringify(answer)}`,
      );
    }
  }
  assert.deepEqual([...shapes].sort(), ["decision", "optionId"]);
});

// ---- 应答口 ----

test("waterfallResponderOf：next/respond/bundle 逐个认，绑回原对象；拿不到返回 null", () => {
  const seen = [];
  const withNext = { next: (v) => seen.push(["next", v]) };
  assert.equal(typeof waterfallResponderOf(withNext), "function");
  waterfallResponderOf(withNext)({ decision: "approve" });
  assert.deepEqual(seen, [["next", { decision: "approve" }]]);

  const withRespond = { respond: (v) => seen.push(["respond", v]) };
  waterfallResponderOf(withRespond)({ optionId: "a" });
  assert.deepEqual(seen[1], ["respond", { optionId: "a" }]);

  // 嵌套 request 里也认
  const nested = { request: { next: (v) => seen.push(["nested", v]) } };
  waterfallResponderOf(nested)(1);
  assert.deepEqual(seen[2], ["nested", 1]);

  // 载荷本身就是函数
  const bare = (v) => seen.push(["bare", v]);
  waterfallResponderOf(bare)(2);
  assert.deepEqual(seen[3], ["bare", 2]);

  assert.equal(waterfallResponderOf({}), null);
  assert.equal(waterfallResponderOf(null), null);
  assert.equal(waterfallResponderOf({ next: "not-a-fn" }), null);
});

test("answerVia：送达返回 true；应答口抛错吞掉返回 false；不答/非函数不碰", () => {
  assert.equal(answerVia((v) => v, { decision: "approve" }), true);
  assert.equal(
    answerVia(() => {
      throw new Error("waterfall 已关闭");
    }, { optionId: "a" }),
    false,
    "抛错吞掉",
  );
  assert.equal(answerVia(null, { decision: "approve" }), false);
  const touched = [];
  assert.equal(answerVia((v) => touched.push(v), null), false);
  assert.deepEqual(touched, [], "没有应答值就不该碰应答口");
});

// ---- 选项表 ----

test("questionOptionsOf：对象/字符串条目都认，带 id+label；坏条目跳过", () => {
  const payload = {
    options: [{ id: "a", label: "方案 A" }, "b", { value: "c", text: "方案 C" }, { label: "缺 id" }, null],
  };
  assert.deepEqual(questionOptionsOf(payload), [
    { id: "a", label: "方案 A" },
    { id: "b", label: "b" },
    { id: "c", label: "方案 C" },
  ]);
  assert.deepEqual(questionOptionIdsOf(payload), ["a", "b", "c"]);
  // 字段漂移防御：choices/items/answers 也认
  assert.deepEqual(questionOptionIdsOf({ choices: [{ key: "k1" }] }), ["k1"]);
  assert.deepEqual(questionOptionIdsOf({ items: ["x"] }), ["x"]);
  assert.deepEqual(questionOptionIdsOf({}), []);
});

test("sanitizeOptions：钩子体选项容错清洗与手机侧同口径", () => {
  assert.deepEqual(
    sanitizeOptions([{ id: "a", label: "A" }, { label: "缺 id" }, "b", 42]),
    [{ id: "a", label: "A" }, { id: "b", label: "b" }],
  );
  assert.deepEqual(sanitizeOptions("not-array"), []);
});
