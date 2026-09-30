/**
 * DSH 只读订阅插件（ADR 0010 / spec 0018-1 票 #171；批准应答通道 spec 0018-6 票 #176）：
 * 挂在 DSH 内的 host 插件，把官方会话事件转发给本机 RearCue PC 桥的 `/hooks/dsh`，
 * 供背屏 Agent Mirror 镜像；等待确认/提问的**批准类应答**在瀑布请求存续期经桥
 * `/action/pending` 取一次性决定后回给官方 waterfall（ADR 0010：应答通道天然限定批准类）。
 *
 * **只读保证（判例锁死于 dsh-plugin.test.mjs）**：
 * - 只用 `ctx.remote.$on` 订阅事件，其余远端接口（任何带副作用的调用）一律不碰；
 * - 对外出口只有两个，都是本机回环：`/hooks/dsh` 转发（fire-and-forget）与
 *   `/action/pending` 取批准决定（`&plugin=dsh`，兼作插件活性心跳）；
 * - waterfall 应答**只经 dsh-answers.mjs 的规范应答值**（同意/拒绝/选项 id），
 *   自由文字输入在应答形态里根本不存在（ADR 0009 红线）；
 * - 转发/应答失败只吞不抛：插件绝不影响 DSH 本身（与 claude-hook.mjs 的红线同族）。
 *
 * 事件名订阅面见 [SUBSCRIBED_EVENTS]；形状搬运全部在 dsh-events.mjs / dsh-answers.mjs 的
 * 纯函数里，本文件只有「订阅 + 转发 + 批准应答」的壳。版本门槛 DSH >= 0.2.0-rc.2
 * （package.json 声明 ＋ 启动时运行时核对 [versionAtLeast]，不达标即零订阅零转发）。真机 DSH 环境联调在集成验收票（#178）——本机无可连 DSH，
 * 插件逻辑以 fixture 判例全测。
 */
import { answerVia, dshAnswerFor, questionOptionIdsOf, waterfallResponderOf } from "./dsh-answers.mjs";
import { dshEventToHookBody, SUBSCRIBED_EVENTS } from "./dsh-events.mjs";

export const DSH_MIN_VERSION = "0.2.0-rc.2";
const DEFAULT_PORT = Number(process.env.BRIDGE_PORT || 18787);

/**
 * 解版本串为可比较形态（`x.y.z[-预发布段…]`）；认不出返回 null。
 * 预发布段逐段比，纯数字段按数值（`rc.10 > rc.2`，不按字典序）。
 */
function parseVersion(v) {
  if (typeof v !== "string") return null;
  const m = v.trim().match(/^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?/);
  if (!m) return null;
  return { nums: [Number(m[1]), Number(m[2]), Number(m[3])], pre: m[4] ? m[4].split(".") : [] };
}

/**
 * 版本门槛判定（review 2026-09-30 追加 / ADR 0010 门槛，spec 0018-1 AC1）：
 * `version >= min` 才放行。**认不出的版本不挡**（fail-open）——安装门槛由 package.json
 * 的 engines 把关，这里只拦「明确低于门槛」的运行时残留（比如旧引擎挂着新插件）。
 */
export function versionAtLeast(version, min = DSH_MIN_VERSION) {
  const a = parseVersion(version);
  const b = parseVersion(min);
  if (!a || !b) return true;
  for (let i = 0; i < 3; i++) {
    if (a.nums[i] !== b.nums[i]) return a.nums[i] > b.nums[i];
  }
  if (a.pre.length === 0 && b.pre.length === 0) return true;
  if (a.pre.length === 0) return true; // 无预发布 > 有预发布（0.2.0 > 0.2.0-rc.2）
  if (b.pre.length === 0) return false;
  const n = Math.max(a.pre.length, b.pre.length);
  for (let i = 0; i < n; i++) {
    const x = a.pre[i];
    const y = b.pre[i];
    if (x === undefined) return false;
    if (y === undefined) return true;
    const nx = /^\d+$/.test(x);
    const ny = /^\d+$/.test(y);
    if (nx && ny) {
      const d = Number(x) - Number(y);
      if (d !== 0) return d > 0;
    } else if (x !== y) {
      return x > y;
    }
  }
  return true;
}

/** 从插件宿主 ctx 认 dsh 引擎版本（字段跨版本漂移，认不出回 null＝不挡）。 */
function engineVersionOf(ctx) {
  return ctx?.engine?.dsh?.version ?? ctx?.version ?? null;
}
/**
 * 批准等待窗：瀑布请求本来就在等机主，开着等无副作用（DSH 空转），默认 30 分钟；
 * 0＝只试一次（桥上已排队的决定照常取走）。可由 DSH_APPROVE_WAIT_MS 覆盖。
 */
const DEFAULT_WAIT_MS = Number(process.env.DSH_APPROVE_WAIT_MS || 1_800_000);
const DEFAULT_INTERVAL_MS = Number(process.env.DSH_APPROVE_POLL_MS || 2000);

/** 默认转发器：POST 本机桥 `/hooks/dsh`，2s 超时，失败静默（绝不影响 DSH）。 */
function defaultPost(port, body) {
  fetch(`http://127.0.0.1:${port}/hooks/dsh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(2000),
  }).catch(() => {
    /* 桥未启动/超时：静默 */
  });
}

/**
 * 默认取决定器：GET 本机桥 `/action/pending`（一次性、过期即无；`&plugin=dsh` 兼作
 * 插件活性心跳），失败回 null（当次没决定，继续等）。
 */
function defaultPoll(port, sessionId) {
  return fetch(
    `http://127.0.0.1:${port}/action/pending?sessionId=${encodeURIComponent(sessionId)}&plugin=dsh`,
    { signal: AbortSignal.timeout(2000) },
  )
    .then((r) => (r.ok ? r.json() : null))
    .catch(() => null);
}

/** 桥的决定条目 → 动作词（approve|reject|select）＋选项 id；认不出返回 {action:null}。 */
function actionFromEntry(kind, entry) {
  if (!entry || typeof entry !== "object") return { action: null, optionId: null };
  if (kind === "question") {
    return { action: "select", optionId: typeof entry.optionId === "string" ? entry.optionId : null };
  }
  if (entry.decision === "deny") return { action: "reject", optionId: null };
  if (entry.decision === "allow") return { action: "approve", optionId: null };
  return { action: null, optionId: null };
}

/**
 * 批准应答流（票 #176，导出供判例）：瀑布请求存续期轮询桥上的远程决定，取到即回
 * **规范应答**（dshAnswerFor 两种形状之外不答）；预算内没等到就不答——官方请求保持
 * 等待，绝不自由发挥。应答口抛错/取决定失败全部吞掉，绝不影响 DSH。
 */
export async function runAnswerFlow({ kind, sessionId, payload, poll, port, waitMs, intervalMs }) {
  const responder = waterfallResponderOf(payload);
  if (!responder) return false;
  const optionIds = kind === "question" ? questionOptionIdsOf(payload) : [];
  const budget = Number.isFinite(waitMs) ? waitMs : DEFAULT_WAIT_MS;
  const gap = Number.isFinite(intervalMs) ? intervalMs : DEFAULT_INTERVAL_MS;
  const deadline = Date.now() + Math.max(budget, 0);
  for (;;) {
    let entry = null;
    try {
      entry = await poll(port, sessionId);
    } catch {
      entry = null;
    }
    const { action, optionId } = actionFromEntry(kind, entry);
    const answer = dshAnswerFor(kind, action, optionId, optionIds);
    if (answer) return answerVia(responder, answer);
    if (Date.now() >= deadline) return false;
    await new Promise((r) => setTimeout(r, Math.max(gap, 1)));
  }
}

/**
 * 构造插件实例。可注入（判例用）：`options.post(port, body)` 转发器、
 * `options.poll(port, sessionId)` 取决定器、`options.waitMs`/`options.intervalMs` 等待窗、
 * `options.engineVersion` 引擎版本（缺省从 ctx 认，认不出不挡）。
 * 缺省分别走 [defaultPost] / [defaultPoll] / 环境常量。返回 cordis 风格的 { name, apply(ctx) }。
 */
export function createDshBridgePlugin(options = {}) {
  const port = options.port ?? DEFAULT_PORT;
  const post = options.post ?? defaultPost;
  const poll = options.poll ?? defaultPoll;
  const waitMs = options.waitMs;
  const intervalMs = options.intervalMs;
  return {
    name: "dsh-bridge-readonly",
    apply(ctx) {
      // 运行时版本门槛（review 2026-09-30 / ADR 0010）：明确低于 DSH_MIN_VERSION 的引擎
      // 一律不订阅、不转发任何事件（= DSH 来源不出现，其余来源不受牵连），日志一行说原因。
      const engineVersion = options.engineVersion ?? engineVersionOf(ctx);
      if (!versionAtLeast(engineVersion)) {
        console.warn(
          `[dsh-bridge-readonly] dsh 引擎版本 ${engineVersion} 低于 ${DSH_MIN_VERSION}，不转发任何事件（DSH 来源不出现）`,
        );
        return;
      }
      for (const name of SUBSCRIBED_EVENTS) {
        ctx.remote.$on(name, (payload) => {
          try {
            const body = dshEventToHookBody(name, payload);
            if (!body) return;
            post(port, body);
            // 等待确认/提问：官方瀑布请求在等机主，同步开批准应答流（fire-and-forget）。
            const kind =
              body.event === "approval-request" ? "approval" : body.event === "question-request" ? "question" : null;
            if (kind) {
              runAnswerFlow({ kind, sessionId: body.sessionId, payload, poll, port, waitMs, intervalMs }).catch(
                () => {
                  /* 应答失败只吞不抛 */
                },
              );
            }
          } catch {
            /* 单条事件转发失败绝不影响 DSH */
          }
        });
      }
    },
  };
}

export default createDshBridgePlugin();
