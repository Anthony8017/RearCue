/**
 * DSH 批准类应答的纯函数（spec 0018-6 / 票 #176；ADR 0010「应答通道天然限定批准类」）。
 *
 * 红线（ADR 0009）：应答**只有两类**——批准决定（同意/拒绝）与选择题选项点选。
 * 自由文字输入在应答形态里根本不存在：[dshAnswerFor] 的输出恰好两种形状
 * `{decision:"approve"|"reject"}` 或 `{optionId}`，此外一律返回 null（不答、绝不造答案）。
 *
 * - [waterfallResponderOf]：从官方 waterfall 载荷取应答口（字段跨版本漂移，逐个认）；
 * - [questionOptionsOf] / [questionOptionIdsOf]：提问载荷的选项表（选项外的 id 不采信）；
 * - [sanitizeOptions]：钩子体选项表的容错清洗（坏条目跳过，与手机侧同口径）；
 * - [dshAnswerFor]：动作 → 规范应答值（不合法返回 null）；
 * - [answerVia]：把应答值交给应答口；失败吞掉，绝不影响 DSH。
 */

/** 选项条目 → {id, label}；字符串条目整段当 id；都不合格返回 null。 */
function idLabelOf(o) {
  if (typeof o === "string" && o.trim()) {
    const id = o.trim();
    return { id, label: id };
  }
  if (o && typeof o === "object") {
    for (const key of ["id", "value", "key", "optionId"]) {
      const v = o[key];
      if (typeof v === "string" && v.trim()) {
        const id = v.trim();
        let label = id;
        for (const lk of ["label", "text", "title", "name"]) {
          const lv = o[lk];
          if (typeof lv === "string" && lv.trim()) {
            label = lv.trim();
            break;
          }
        }
        return { id, label };
      }
    }
  }
  return null;
}

/**
 * 官方 waterfall 载荷 → 应答口（函数）。认 `next`/`respond`/`reply` 等常见名（字段漂移防御），
 * 载荷本身是函数也认；拿不到返回 null（调用方不答）。
 */
export function waterfallResponderOf(payload) {
  if (typeof payload === "function") return payload;
  const p = payload && typeof payload === "object" ? payload : {};
  const r = p.request && typeof p.request === "object" ? p.request : null;
  for (const key of ["next", "respond", "reply", "resolve", "submit", "answer", "callback"]) {
    if (typeof p[key] === "function") return p[key].bind(p);
    if (r && typeof r[key] === "function") return r[key].bind(r);
  }
  return null;
}

/** 提问载荷 → 选项表 [{id,label}]（options/choices/items/answers 逐个认）。 */
export function questionOptionsOf(payload) {
  const p = payload && typeof payload === "object" ? payload : {};
  const raw = [p.options, p.choices, p.items, p.answers].find((v) => Array.isArray(v)) || [];
  return raw.map(idLabelOf).filter(Boolean);
}

/** 提问载荷 → 选项 id 表（选择题点选的合法性判据）。 */
export function questionOptionIdsOf(payload) {
  return questionOptionsOf(payload).map((o) => o.id);
}

/** 钩子体选项表容错清洗（坏条目跳过，与手机侧 BridgeEventCodec 同口径）。 */
export function sanitizeOptions(raw) {
  if (!Array.isArray(raw)) return [];
  return raw.map(idLabelOf).filter(Boolean);
}

/**
 * 动作 → 规范应答值（纯函数，判例锁死）：
 * - kind=approval：approve→{decision:"approve"}、reject→{decision:"reject"}（select 不适用→null）；
 * - kind=question：select＋选项在表→{optionId}（approve/reject 不适用→null；表非空时表外 id→null）；
 * - 其余一律 null。**输出恰好两种形状，没有第三种，也没有任何自由文字面。**
 */
export function dshAnswerFor(kind, action, optionId, optionIds) {
  if (kind === "approval") {
    if (action === "approve") return { decision: "approve" };
    if (action === "reject") return { decision: "reject" };
    return null;
  }
  if (kind === "question") {
    if (action !== "select") return null;
    if (typeof optionId !== "string" || !optionId) return null;
    const ids = Array.isArray(optionIds) ? optionIds : [];
    if (ids.length > 0 && !ids.includes(optionId)) return null;
    return { optionId };
  }
  return null;
}

/** 把规范应答值交给应答口；返回是否送达。应答口抛错吞掉（绝不影响 DSH）。 */
export function answerVia(responder, answer) {
  if (typeof responder !== "function" || !answer) return false;
  try {
    responder(answer);
    return true;
  } catch {
    return false;
  }
}
