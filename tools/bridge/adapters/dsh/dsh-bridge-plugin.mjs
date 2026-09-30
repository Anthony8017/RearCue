/**
 * DSH 只读订阅插件（ADR 0010 / spec 0018-1 票 #171；批准应答通道 0018-6 票 #176；
 * **宿主侧重写 票 #181**）：挂在 DSH 宿主侧，把官方会话事件转发给本机 RearCue PC 桥的
 * `/hooks/dsh`，供背屏 Agent Mirror 镜像；等待确认/提问的**批准类应答**在 waterfall
 * 请求存续期经桥 `/action/pending` 取一次性决定后回给官方 waterfall。
 *
 * 为什么是宿主侧（真机联调 2026-09-30 / 票 #181 证据）：
 * - 客户端转发面 `ctx.remote.$on` 只含 `api-session/*` 等会话级事件，**对话正文
 *   （user/message、assistant/message）根本不在集合里**，且参数是 `(sessionId, running)`
 *   这类位置参数；宿主侧 `ctx.on` 才拿得到正文与真实形状。
 * - 宿主侧不依赖窗口是否打开，随 app 启动即生效。
 *
 * **只读保证（判例锁死于 dsh-plugin.test.mjs）**：
 * - 只用 `ctx.on` 订阅事件，不取任何 Service、不调用任何带副作用的接口；
 * - 对外出口只有两个，都是本机回环：`/hooks/dsh` 转发（fire-and-forget）与
 *   `/action/pending` 取批准决定（`&plugin=dsh`，兼作插件活性心跳）；
 * - waterfall 应答**只经 dsh-answers.mjs 的规范应答值**（`allowed-once`/`rejected` 或
 *   `{answers:[{id,selected}]}`），自由文字输入在应答形态里根本不存在（ADR 0009 红线）；
 *   取不到决定就委派 `next()`，绝不猜；
 * - 转发/应答失败只吞不抛：插件绝不影响 DSH 本身。
 *
 * 版本门槛 DSH >= 0.2.0-rc.2（package.json 声明 ＋ 启动时运行时核对 [versionAtLeast]，
 * 不达标即零订阅零转发）。
 */
import { actionFromEntry, approvalOutcomeFor, questionAnswerFor } from "./dsh-answers.mjs";
import { hookBodiesFor, questionAskOf, SUBSCRIBED_EVENTS } from "./dsh-events.mjs";

export const DSH_MIN_VERSION = "0.2.0-rc.2";
const DEFAULT_PORT = Number(process.env.BRIDGE_PORT || 18787);

/** 插件名（宿主 cordis 插件契约：命名导出 apply ＋ 可选 inject）。 */
export const name = "dsh-bridge-readonly";

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
 * 版本门槛判定（ADR 0010 门槛，spec 0018-1 AC1）：`version >= min` 才放行。
 * **认不出的版本不挡**（fail-open）——安装门槛由 package.json 的 engines 把关，
 * 这里只拦「明确低于门槛」的运行时残留（比如旧引擎挂着新插件）。
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
 * 批准等待窗：waterfall 请求本来就在等机主，开着等无副作用（DSH 空转），默认 30 分钟；
 * 0＝只试一次（桥上已排队的决定照常取走）。可由 DSH_APPROVE_WAIT_MS 覆盖。
 */
const DEFAULT_WAIT_MS = Number(process.env.DSH_APPROVE_WAIT_MS || 1_800_000);
const DEFAULT_INTERVAL_MS = Number(process.env.DSH_APPROVE_POLL_MS || 2000);
/** 流式增量合并窗：把逐字 delta 攒成一两条再发，避免把桥刷爆。 */
const DEFAULT_DELTA_GAP_MS = 250;

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

/**
 * 挂载心跳（票 #181）：apply 成功即打一次既有活性口（与批准轮询同一口、同一语义），
 * 桥侧 `dsh` 能力随插件露面——验收只读 `/snapshot` 即可确认「插件挂上了」；
 * 空 sessionId 取不到任何待决条目，纯心跳、无副作用。
 */
function defaultAnnounce(port) {
  fetch(`http://127.0.0.1:${port}/action/pending?sessionId=&plugin=dsh`, {
    signal: AbortSignal.timeout(2000),
  }).catch(() => {
    /* 桥未启动/超时：静默 */
  });
}

/**
 * 轮询桥上的远程决定直到拿到（或等待窗结束）。
 * 返回 `{action, optionId}`；超时/失败返回 `{action:null}`（调用方委派 next()）。
 */
export async function waitForDecision({ sessionId, poll, port, waitMs, intervalMs }) {
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
    const picked = actionFromEntry(entry);
    if (picked.action) return picked;
    if (Date.now() >= deadline) return { action: null, optionId: null };
    await new Promise((r) => setTimeout(r, Math.max(gap, 1)));
  }
}

/**
 * 批准应答流（票 #176 / #181）：等手机决定，拿到就回**规范应答值**
 * （`allowed-once` / `rejected`），否则委派 `next()`（官方请求继续按常规路径问人）。
 * 任何异常都不影响 DSH：一律落回 `next()`。
 */
export async function runApprovalFlow({ sessionId, poll, port, waitMs, intervalMs, next }) {
  const { action } = await waitForDecision({ sessionId, poll, port, waitMs, intervalMs });
  const outcome = approvalOutcomeFor(action);
  if (outcome) return outcome;
  return typeof next === "function" ? next() : "unavailable";
}

/**
 * 提问应答流（票 #176 / #181）：等手机点选，拿到就回 `{answers:[{id, selected:[label]}]}`，
 * 否则委派 `next()`。提问没有「拒绝」应答形态，reject 等同委派。
 */
export async function runQuestionFlow({ sessionId, ask, poll, port, waitMs, intervalMs, next }) {
  const { action, optionId } = await waitForDecision({ sessionId, poll, port, waitMs, intervalMs });
  const answer = questionAnswerFor(action, optionId, ask);
  if (answer) return answer;
  return typeof next === "function" ? next() : { answers: [] };
}

/**
 * 流式增量合并器：逐字 delta 先在本地按会话攒 gapMs，再一次 POST（桥侧只做追加）。
 * 返回 `{ push(sessionId, text), flush() }`——flush 供测试与退出前收尾。
 */
export function createDeltaCoalescer(post, port, gapMs = DEFAULT_DELTA_GAP_MS) {
  const buffers = new Map();
  let timer = null;
  const flush = () => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
    const pending = [...buffers];
    buffers.clear();
    for (const [sessionId, text] of pending) {
      if (text) post(port, { event: "assistant-delta", sessionId, assistantDelta: text });
    }
  };
  const push = (sessionId, text) => {
    if (!sessionId || !text) return;
    buffers.set(sessionId, (buffers.get(sessionId) ?? "") + text);
    if (gapMs <= 0) flush();
    else if (timer === null) timer = setTimeout(flush, gapMs);
  };
  return { push, flush };
}

/**
 * 构造插件实例。可注入（判例用）：`options.post(port, body)` 转发器、
 * `options.poll(port, sessionId)` 取决定器、`options.waitMs`/`options.intervalMs` 等待窗、
 * `options.deltaGapMs` 增量合并窗、`options.engineVersion` 引擎版本（缺省从 ctx 认）。
 * 返回 cordis 风格的 `{ name, apply(ctx) }`。
 */
export function createDshBridgePlugin(options = {}) {
  const port = options.port ?? DEFAULT_PORT;
  const post = options.post ?? defaultPost;
  const poll = options.poll ?? defaultPoll;
  const waitMs = options.waitMs;
  const intervalMs = options.intervalMs;
  // 判例注入 post 时不打心跳（保持「零外发」判例口径）；生产默认挂载即打一次。
  const announce = options.announce ?? (options.post ? () => {} : defaultAnnounce);
  const coalescer =
    options.coalescer ?? createDeltaCoalescer(post, port, options.deltaGapMs ?? DEFAULT_DELTA_GAP_MS);
  return {
    name,
    apply(ctx) {
      // 宿主侧才有的订阅面：没有 ctx.on 的宿主环境安静退出（绝不碰其他接口）。
      if (typeof ctx?.on !== "function") {
        console.info("[dsh-bridge-readonly] ctx.on 不可用，退出订阅");
        return;
      }
      // 运行时版本门槛（ADR 0010）：明确低于 DSH_MIN_VERSION 的引擎一律零订阅零转发
      // （= DSH 来源不出现，其余来源不受牵连），日志一行说原因。
      const engineVersion = options.engineVersion ?? engineVersionOf(ctx);
      if (!versionAtLeast(engineVersion)) {
        console.warn(
          `[dsh-bridge-readonly] dsh 引擎版本 ${engineVersion} 低于 ${DSH_MIN_VERSION}，不转发任何事件（DSH 来源不出现）`,
        );
        return;
      }
      announce(port);

      /** 单条事件 → 钩子体 → 转发（任何异常只吞不抛，绝不影响 DSH）。 */
      const forward = (eventName, args) => {
        try {
          for (const body of hookBodiesFor(eventName, ...args)) {
            if (body.event === "assistant-delta") coalescer.push(body.sessionId, body.assistantDelta);
            else post(port, body);
          }
        } catch {
          /* 单条事件转发失败绝不影响 DSH */
        }
      };

      // 普通事件：订阅 + 转发。事件名订不上（非法键/未声明）只跳过该条。
      for (const eventName of SUBSCRIBED_EVENTS) {
        if (eventName === "approval/request" || eventName === "user-questions/request") continue; // waterfall 单独接
        try {
          ctx.on(eventName, (...args) => forward(eventName, args));
        } catch {
          /* 跳过该条，继续订其余 */
        }
      }

      // 批准 waterfall（票 #176）：先转发（手机据此亮等待标记），再等手机决定；
      // 拿到决定回规范应答值（allowed-once / rejected），否则委派 next()。
      try {
        ctx.on("approval/request", (req, next) => {
          const sessionId = req?.agent?.id ?? null;
          forward("approval/request", [req]);
          if (!sessionId) return typeof next === "function" ? next() : "unavailable";
          return runApprovalFlow({ sessionId, poll, port, waitMs, intervalMs, next }).catch(() =>
            typeof next === "function" ? next() : "unavailable",
          );
        });
      } catch {
        /* 订不上：不提供远程批准，其余照常 */
      }

      // 提问 waterfall（票 #176 / #181）：选项没有 id，用 label 当 id 进 pendingOptions；
      // 手机点选的 label 原样回 `{answers:[{id, selected:[label]}]}`。
      try {
        ctx.on("user-questions/request", (req, next) => {
          const sessionId = req?.agent?.id ?? null;
          const ask = questionAskOf(req);
          forward("user-questions/request", [req]);
          if (!sessionId || !ask) return typeof next === "function" ? next() : { answers: [] };
          return runQuestionFlow({ sessionId, ask, poll, port, waitMs, intervalMs, next }).catch(() =>
            typeof next === "function" ? next() : { answers: [] },
          );
        });
      } catch {
        /* 订不上：不提供远程点选，其余照常 */
      }

      // 插件卸载/退出前把攒着的增量冲出去（只写本机桥，不改 DSH 状态）。
      return () => coalescer.flush();
    },
  };
}

/**
 * 宿主 cordis 插件入口（契约：命名导出 `apply(ctx, config)`，不与默认导出混用）。
 * 判例仍用 [createDshBridgePlugin] 注入替身。
 */
export function apply(ctx, config) {
  return createDshBridgePlugin(config ?? {}).apply(ctx);
}
