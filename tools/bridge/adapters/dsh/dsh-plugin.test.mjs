// DSH 只读插件判例（票 #171）：node --test tools/bridge/adapters/dsh/dsh-plugin.test.mjs
// 行为面：订阅接线 + 事件转发（fake ctx 注入）；红线面：**只读保证**——源码扫描锁死
// 「只订阅、绝不调用任何写方法、唯一出口是本机桥 /hooks/dsh」（ADR 0010）。
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createDshBridgePlugin, DSH_MIN_VERSION } from "./dsh-bridge-plugin.mjs";
import { SUBSCRIBED_EVENTS } from "./dsh-events.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const PLUGIN_SOURCE = readFileSync(join(HERE, "dsh-bridge-plugin.mjs"), "utf8");
const EVENTS_SOURCE = readFileSync(join(HERE, "dsh-events.mjs"), "utf8");

/** 假 DSH ctx：只提供 remote.$on 订阅面（真实插件也只被允许用这一个口）。 */
function fakeCtx() {
  const handlers = new Map();
  return {
    remote: {
      $on(name, handler) {
        handlers.set(name, handler);
      },
    },
    handlers,
  };
}

test("apply：官方事件全订阅面接上，一个不漏", () => {
  const ctx = fakeCtx();
  createDshBridgePlugin({ post: () => {} }).apply(ctx);
  for (const name of SUBSCRIBED_EVENTS) {
    assert.ok(ctx.handlers.has(name), `缺订阅：${name}`);
  }
});

test("收到官方事件 → 转发一条钩子体到桥（端口随配置）", () => {
  const posted = [];
  const ctx = fakeCtx();
  createDshBridgePlugin({ port: 19001, post: (port, body) => posted.push({ port, body }) }).apply(ctx);
  ctx.handlers.get("api-session/status")({ sessionId: "s-1", status: "running" });
  assert.equal(posted.length, 1);
  assert.equal(posted[0].port, 19001);
  assert.deepEqual(posted[0].body, { event: "session-status", sessionId: "s-1", status: "running" });
});

test("正文流事件转发钩子体；空正文/坏载荷不转发", () => {
  const posted = [];
  const ctx = fakeCtx();
  createDshBridgePlugin({ post: (_port, body) => posted.push(body) }).apply(ctx);
  ctx.handlers.get("assistant/message")({ sessionId: "s-1", delta: "逐批" });
  assert.deepEqual(posted[0], { event: "assistant-delta", sessionId: "s-1", assistantDelta: "逐批" });
  ctx.handlers.get("user/message")({ sessionId: "s-1" }); // 空正文：不转发
  ctx.handlers.get("api-session/added")(null); // 坏载荷：不转发不抛
  assert.equal(posted.length, 1);
});

test("转发器抛错不影响 DSH（红线：插件绝不影响会话）", () => {
  const ctx = fakeCtx();
  createDshBridgePlugin({
    post: () => {
      throw new Error("桥未启动");
    },
  }).apply(ctx);
  assert.doesNotThrow(() => ctx.handlers.get("approval/request")({ sessionId: "s-1", title: "拍板" }));
});

// ---- 只读保证（判例锁红线） ----

test("只读保证：ctx.remote 只出现 $on 订阅调用", () => {
  const calls = [...PLUGIN_SOURCE.matchAll(/ctx\.remote\.([A-Za-z_$][\w$]*)\s*\(/g)].map((m) => m[1]);
  assert.ok(calls.length > 0, "应有订阅调用");
  assert.deepEqual([...new Set(calls)], ["$on"], "除 $on 外不得出现任何远端调用面");
});

test("只读保证：插件与映射源码不含任何写方法调用面", () => {
  // 写方法 token 面（副作用调用）：出现即判例红。两份源码都扫。
  const writeCall = /\.(respond|resolve|reject|approve|create|update|remove|delete|send\w*|upload\w*|install\w*)\s*\(/;
  for (const [name, src] of [["dsh-bridge-plugin.mjs", PLUGIN_SOURCE], ["dsh-events.mjs", EVENTS_SOURCE]]) {
    assert.equal(writeCall.test(src), false, `${name} 出现写方法调用面`);
  }
});

test("只读保证：唯一外发出口是本机桥 /hooks/dsh", () => {
  const urls = PLUGIN_SOURCE.match(/https?:\/\/[^\s"'`]+/g) || [];
  assert.ok(urls.length > 0, "应有转发出口");
  for (const u of urls) {
    assert.ok(u.startsWith("http://127.0.0.1:"), `外发地址必须是本机回环：${u}`);
    assert.ok(u.includes("/hooks/dsh"), `外发路径必须是 /hooks/dsh：${u}`);
  }
});

test("版本门槛声明：DSH >= 0.2.0-rc.2（package.json 同步）", () => {
  assert.equal(DSH_MIN_VERSION, "0.2.0-rc.2");
  const manifest = JSON.parse(readFileSync(join(HERE, "package.json"), "utf8"));
  assert.equal(manifest.dsh.engines.dsh, `>=${DSH_MIN_VERSION}`);
});
