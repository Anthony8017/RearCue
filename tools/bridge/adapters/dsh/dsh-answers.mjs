/**
 * 批准/提问的**规范应答值**（ADR 0009 红线 / spec 0018-4、0018-6 / 票 #181 按真机形状重写）。
 *
 * 真机（DSH 0.2.0-rc.2）两条 waterfall 的应答形状与本模块原假设不同，已按 app.asar 证据对齐：
 * - `approval/request`：应答值是字符串 `'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'`
 *   （**不是** `{decision:"allow"|"deny"}`）。「同意」唯一对应 `allowed-once`。
 * - `user-questions/request`：应答是 `{answers:[{id, selected: string[], custom?}]}`，其中
 *   `selected` 装的是**选项 label**（选项对象本身没有 id 字段），`custom` 是自由文字——
 *   自由文字在本模块**根本不存在**（红线：除批准外只读，不提供任何输入面）。
 *
 * 本模块只做「动作 → 规范应答」的纯映射，别的什么都不做（判例锁死：无写方法调用面、
 * 不自造应答形状、不产生自由文字）。取决定的通道（桥 `/action/pending`）在插件里。
 */

/** 批准 waterfall 的规范应答词表（宿主 dsh-user-approval 的 OUTCOMES）。 */
export const APPROVAL_OUTCOMES = ["allowed-once", "rejected", "cancelled", "unavailable"];

/**
 * 手机动作词 → 批准应答值；select 对批准无意义、未知动作返回 null。
 * 返回 null 时调用方必须委派 `next()`（不认领请求），绝不猜一个应答值。
 */
export function approvalOutcomeFor(action) {
  if (action === "approve") return "allowed-once";
  if (action === "reject") return "rejected";
  return null;
}

/**
 * 手机动作 → 提问应答值（`{answers:[…]}`）或 null（无规范应答时调用方委派 next()）。
 * - `select`：optionId 必须是该题实际存在的 label（真机选项没有 id，label 即 id）。
 * - `approve`：计划评审类问题（`intent.approve` 给出「同意」对应的 label）选它，否则选第一个选项。
 * - `reject`：提问没有「拒绝」这一应答形态 → null（委派给下一个 answerer）。
 */
export function questionAnswerFor(action, optionId, ask) {
  if (!ask || !Array.isArray(ask.options) || ask.options.length === 0) return null;
  const labels = ask.options.map((o) => o.label);
  const id = typeof ask.questionId === "string" && ask.questionId ? ask.questionId : "q1";
  if (action === "select") {
    if (typeof optionId !== "string" || !labels.includes(optionId)) return null;
    return { answers: [{ id, selected: [optionId] }] };
  }
  if (action === "approve") {
    const preferred =
      typeof ask.planApproveLabel === "string" && labels.includes(ask.planApproveLabel)
        ? ask.planApproveLabel
        : labels[0];
    return preferred ? { answers: [{ id, selected: [preferred] }] } : null;
  }
  return null;
}

/**
 * 桥 `/action/pending` 的决定条目 → 动作词（approve|reject|select）＋选项 id。
 * 形状（桥侧契约）：`{decision:"allow"|"deny", optionId?}`；带 optionId 的一律按选择题点选。
 * 认不出返回 `{action:null}`（调用方继续等，等到等待窗结束再委派）。
 */
export function actionFromEntry(entry) {
  if (!entry || typeof entry !== "object") return { action: null, optionId: null };
  if (typeof entry.optionId === "string" && entry.optionId) return { action: "select", optionId: entry.optionId };
  if (entry.decision === "deny") return { action: "reject", optionId: null };
  if (entry.decision === "allow") return { action: "approve", optionId: null };
  return { action: null, optionId: null };
}
