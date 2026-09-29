/**
 * 问答流构造（spec 0017 / 票 #169）：把「机主提问」与「agent 输出」按时间顺序攒成一份 turns，
 * 并按尾部窗口（默认最近 20 条 / 16000 字）裁剪——桥每次把**整个 turns 数组**发给手机，
 * 手机端重渲染整段（自愈：漏一帧也能靠下一帧追上）。
 *
 * 为什么窗口在桥侧：电脑端的原始记录是无限长的，手机只需要「最近这一段」；
 * 窗口值与 spec 0010 时代的 `tail-util.createTailHistory` 保持一致（20 条 / 16000 字），
 * 换来的差别是**提问与回答共用同一个窗口**，不再是「只有助手文本被留、提问被丢」。
 *
 * 一条 turn 的形状（与手机端 `AgentTurn` 对齐）：
 *   { role: "user"|"assistant", text: string, ts: number, open?: boolean }
 * `open: true` 表示这一条**仍在增长**（Claude 的 MessageDisplay 中间批、ZCode 的流式增量）。
 */
export function createTurnLog({ maxEntries = 20, maxChars = 16000, separator = "\n\n────────\n\n" } = {}) {
  /** @type {Array<{role:string,text:string,ts:number}>} */
  let turns = [];

  /**
   * 尾部窗口的计量口径：**用户实际看到的那段文本**的长度——各条正文之和加上条目之间的分隔，
   * 而不是纯字符之和。spec 0010 时代的 `tail-util.createTailHistory` 正是按 `join(separator).length`
   * 计量的，本函数保持同口径，免得换代之后同一个 16000 字的会话在屏上变得长短不一。
   */
  const totalChars = () => {
    if (turns.length === 0) return 0;
    const body = turns.reduce((sum, t) => sum + (typeof t.text === "string" ? t.text.length : 0), 0);
    return body + separator.length * (turns.length - 1);
  };

  /** 尾部窗口裁剪：条数与总字符双上限，超限从**最旧**丢。 */
  function trim() {
    while (turns.length > maxEntries) turns.shift();
    while (turns.length > 1 && totalChars() > maxChars) turns.shift();
  }

  /** 与末尾同角色同文视为复读（hooks 与适配器抢答同一段），不新增一条。 */
  function isDuplicateOfLast(role, text) {
    const last = turns[turns.length - 1];
    return !!last && last.role === role && last.text === text;
  }

  return {
    /** 机主提问（电脑端 user 行）。空文忽略。 */
    user(text, ts = Date.now()) {
      const t = (text || "").trim();
      if (!t) return this.list();
      if (isDuplicateOfLast("user", t)) return this.list();
      turns.push({ role: "user", text: t, ts });
      trim();
      return this.list();
    },

    /**
     * agent 输出**一条完整消息**（codex rollout 的 assistant 行、Claude transcript 的助手块）。
     * 与末尾的开放条（[delta] 攒出来的那条）合流：以完整文本为准收口，避免同一段出现两次。
     */
    agent(text, ts = Date.now()) {
      const t = (text || "").trim();
      if (!t) return this.list();
      const last = turns[turns.length - 1];
      if (last && last.role === "assistant" && last.open) {
        last.text = t;
        delete last.open;
        return this.list();
      }
      if (isDuplicateOfLast("assistant", t)) return this.list();
      turns.push({ role: "assistant", text: t, ts });
      trim();
      return this.list();
    },

    /**
     * agent 输出的**增量**（Claude `MessageDisplay` 的新完成行、ZCode 的 `row.delta`）：
     * 追加到末尾那条开放条上；末尾不是开放条就新开一条。增量是「追加」语义，不去重。
     */
    delta(text, ts = Date.now()) {
      if (typeof text !== "string" || !text) return this.list();
      const last = turns[turns.length - 1];
      if (last && last.role === "assistant" && last.open) {
        last.text += text;
      } else {
        turns.push({ role: "assistant", text, ts, open: true });
      }
      trim();
      return this.list();
    },

    /** 会话切换/重置：清空（换会话时旧问答流不跨会话带过去）。 */
    reset() {
      turns = [];
      return this.list();
    },

    list() {
      return turns.map((t) => ({ ...t }));
    },

    /** 派生：末尾最后一条**完整**助手输出（旧字段 `latestReply` 的值，兼容未升级的手机端）。 */
    latestReply() {
      for (let i = turns.length - 1; i >= 0; i--) {
        if (turns[i].role === "assistant") return turns[i].text;
      }
      return null;
    },

    get size() {
      return turns.length;
    },
  };
}
