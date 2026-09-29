/**
 * DSH 只读订阅插件（ADR 0010 / spec 0018-1，票 #171）：挂在 DSH 内的 host 插件，
 * 把官方会话事件转发给本机 RearCue PC 桥的 `/hooks/dsh`，供背屏 Agent Mirror 镜像。
 *
 * **只读保证（判例锁死于 dsh-plugin.test.mjs）**：
 * - 只用 `ctx.remote.$on` 订阅事件，其余远端接口（任何带副作用的调用）一律不碰；
 * - 唯一的对外出口是向本机回环 `/hooks/dsh` 的一次 POST（fire-and-forget）；
 * - 转发失败只吞不抛：插件绝不影响 DSH 本身（与 claude-hook.mjs 的红线同族）。
 *
 * 事件名订阅面见 [SUBSCRIBED_EVENTS]；形状搬运全部在 dsh-events.mjs 的纯函数里，
 * 本文件只有「订阅 + 转发」的壳。版本门槛 DSH >= 0.2.0-rc.2（package.json 声明）。
 * 真机 DSH 环境联调在集成验收票（#178）——本机无可连 DSH，插件逻辑以 fixture 判例全测。
 */
import { dshEventToHookBody, SUBSCRIBED_EVENTS } from "./dsh-events.mjs";

export const DSH_MIN_VERSION = "0.2.0-rc.2";
const DEFAULT_PORT = Number(process.env.BRIDGE_PORT || 18787);

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
 * 构造插件实例。`options.post(port, body)` 可注入（判例用）；缺省走 [defaultPost]。
 * 返回 cordis 风格的 { name, apply(ctx) }：apply 里逐事件名订阅，收到即转钩子体。
 */
export function createDshBridgePlugin(options = {}) {
  const port = options.port ?? DEFAULT_PORT;
  const post = options.post ?? defaultPost;
  return {
    name: "dsh-bridge-readonly",
    apply(ctx) {
      for (const name of SUBSCRIBED_EVENTS) {
        ctx.remote.$on(name, (payload) => {
          try {
            const body = dshEventToHookBody(name, payload);
            if (body) post(port, body);
          } catch {
            /* 单条事件转发失败绝不影响 DSH */
          }
        });
      }
    },
  };
}

export default createDshBridgePlugin();
