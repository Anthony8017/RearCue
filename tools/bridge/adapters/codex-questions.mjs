/** Desktop async questions are independent of task status (spec 0027). */
export function codexQuestionReplies(text) {
  if (typeof text !== "string") return [];
  const match = text.trim().match(/^<send_user_message_question_reply>\s*([\s\S]*?)\s*<\/send_user_message_question_reply>$/);
  if (!match) return [];
  try {
    const rows = JSON.parse(match[1]);
    if (!Array.isArray(rows)) return [];
    return rows.flatMap((row) => {
      try {
        const [tool, callId, index] = JSON.parse(row.questionItemId);
        return tool === "request_user_input_async" && typeof callId === "string" &&
          Number.isInteger(index) && index >= 0 && typeof row.answer === "string"
          ? [{ id: `${callId}:${index}`, question: row.question, answer: row.answer }] : [];
      } catch { return []; }
    });
  } catch { return []; }
}

export class CodexQuestionTracker {
  currentTurnId = null;
  status = "idle";
  questions = new Map();
  endedTurns = new Set();
  answered = new Set();

  get pendingQuestions() {
    return [...this.questions.values()].map(({ turnId, ...question }) => question);
  }

  apply(record) {
    const before = JSON.stringify(this.pendingQuestions);
    const p = record?.payload;
    if (!p) return false;
    if (record.type === "event_msg" && p.type === "task_started") {
      const id = p.turn_id;
      if (typeof id === "string") {
        if (id !== this.currentTurnId) this.questions.clear();
        this.currentTurnId = id;
      }
      this.status = "working";
    } else if (record.type === "event_msg" && ["task_complete", "turn_aborted"].includes(p.type)) {
      if (typeof p.turn_id === "string") {
        this.endedTurns.add(p.turn_id);
        for (const [id, question] of this.questions) {
          if (question.turnId === p.turn_id) this.questions.delete(id);
        }
        if (p.turn_id === this.currentTurnId) this.status = "idle";
      }
    } else if (record.type === "event_msg" && p.type === "item_completed" &&
      p.item?.type === "AgentMessage" && p.item.delivery === "async" && Array.isArray(p.item.questions)) {
      const turnId = p.turn_id;
      const callId = p.item.id;
      if (typeof turnId === "string" && typeof callId === "string" &&
        !this.endedTurns.has(turnId) && (!this.currentTurnId || this.currentTurnId === turnId)) {
        this.currentTurnId = turnId;
        this.status = "working";
        p.item.questions.forEach((question, index) => {
          const id = `${callId}:${index}`;
          const title = question?.title;
          if (typeof title !== "string" || !title.trim() || this.answered.has(id)) return;
          const options = (Array.isArray(question.options) ? question.options : []).flatMap((option) => {
            const label = typeof option === "string" ? option : option?.label;
            return typeof label === "string" && label.trim() ? [label.trim()] : [];
          });
          this.questions.set(id, { id, title: title.trim(), options, turnId });
        });
      }
    } else if (record.type === "response_item" && p.type === "message" && p.role === "user") {
      const text = (p.content || []).map((c) => typeof c?.text === "string" ? c.text : "").join("");
      for (const reply of codexQuestionReplies(text)) {
        this.answered.add(reply.id);
        this.questions.delete(reply.id);
      }
    }
    return before !== JSON.stringify(this.pendingQuestions);
  }
}
