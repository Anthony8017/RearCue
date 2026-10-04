import { createHash } from "node:crypto";

const fingerprint = (value) => createHash("sha256").update(String(value)).digest("hex").slice(0, 24);
const text = (value) => typeof value === "string" ? value.trim() : "";
const identity = (value) => typeof value === "number" ? String(value) : text(value);

/** A request is independent of running/idle: async acceptance and tool activity do not answer it. */
export function questionRequests(requestId, questions, createdAt = Date.now()) {
  if (!identity(requestId)) return [];
  return (Array.isArray(questions) ? questions : []).flatMap((question, index) => {
    const prompt = text(question?.question) || text(question?.title) || text(question?.header);
    if (!prompt) return [];
    const options = (Array.isArray(question.options) ? question.options : []).map((option) => {
      if (typeof option === "string") return text(option);
      return [text(option?.label), text(option?.description)].filter(Boolean).join("：");
    }).filter(Boolean);
    return [{ id: `${requestId}:${index}`, kind: "question", title: prompt, options,
      text: [prompt, ...options.map((option, i) => `${i + 1}，${option}`)].join("。"), createdAt }];
  });
}

export function questionReplyIds(value) {
  const match = text(value).match(/^<send_user_message_question_reply>\s*([\s\S]+?)\s*<\/send_user_message_question_reply>$/);
  if (!match) return null;
  try {
    return JSON.parse(match[1]).flatMap((reply) => {
      try {
        const [, callId, index] = JSON.parse(reply.questionItemId);
        return typeof callId === "string" && Number.isInteger(index) ? [`${callId}:${index}`] : [];
      } catch { return []; }
    });
  } catch { return null; }
}

/** Explicit terminal facts only. Status changes, tool failures and snapshots cannot finish a task. */
export class SessionSpeechFacts {
  #sessions = new Map();

  apply(sessionId, patch, now) {
    let state = this.#sessions.get(sessionId);
    if (!state) {
      state = { run: null, counter: 0, answer: "", voiceEvent: null, requests: new Map(), terminal: false, completedRuns: new Set() };
      this.#sessions.set(sessionId, state);
    }
    const at = Number.isFinite(patch.sourceAt) ? patch.sourceAt : now;
    state.voiceEvent = null;
    const explicitRun = identity(patch.turnId);
    if (explicitRun && state.completedRuns.has(explicitRun) && (patch.completion || patch.taskStarted)) {
      return { voiceEvent: null, pendingRequests: [...state.requests.values()] };
    }
    const prompt = text(patch.userText);
    const replies = questionReplyIds(prompt);
    if (patch.taskStarted || (prompt && replies === null && !patch.resolvedRequestPrefix && !patch.resolvedRequestIds)) {
      const run = identity(patch.turnId) || (prompt ? `prompt:${fingerprint(prompt)}:${at}` : null);
      if (!run || run !== state.run) {
        state.run = run || `run:${++state.counter}:${at}`;
        state.answer = "";
        state.terminal = false;
      }
      if (prompt) state.requests.clear();
    }
    if (patch.clearInputRequests === true) state.requests.clear();
    // The pending-question UI model is authoritative when supplied by a source tracker.
    if (Array.isArray(patch.pendingQuestions)) {
      for (const [id, request] of state.requests) if (request.kind === "question") state.requests.delete(id);
      for (const question of patch.pendingQuestions) {
        if (!text(question.id) || !text(question.title)) continue;
        const options = (question.options || []).map((option) => typeof option === "string" ? option : option.label).filter(Boolean);
        state.requests.set(question.id, { id: question.id, kind: "question", title: question.title, options,
          text: [question.title, ...options].join("。"), createdAt: at });
      }
    }
    if (patch.completion === "error" || patch.completion === "cancelled") state.requests.clear();
    if (patch.completion === "done") {
      for (const [id, request] of state.requests) if (request.legacy) state.requests.delete(id);
    }
    for (const id of replies || patch.resolvedRequestIds || []) state.requests.delete(id);
    if (patch.resolvedRequestPrefix) {
      for (const id of state.requests.keys()) if (id.startsWith(`${patch.resolvedRequestPrefix}:`)) state.requests.delete(id);
    }
    for (const prefix of patch.resolvedRequestPrefixes || []) {
      for (const id of state.requests.keys()) if (id.startsWith(`${prefix}:`)) state.requests.delete(id);
    }
    for (const request of patch.inputRequests || []) {
      for (const id of state.requests.keys()) if (id.startsWith("waiting:")) state.requests.delete(id);
      if (text(request?.id) && text(request?.text)) state.requests.set(request.id, { ...request, createdAt: request.createdAt ?? at });
    }
    // Legacy explicit waiting facts retain their request until a source resolution, never a generic working patch.
    if (patch.status === "waiting" && !patch.inputRequests && !state.requests.size) {
      const options = (patch.pendingOptions || []).map((option) => [text(option.label), text(option.description)].filter(Boolean).join("："));
      const body = [text(patch.summary) || "此会话需要你确认", ...options].join("。");
      const id = text(patch.requestId) || `waiting:${fingerprint(body)}`;
      state.requests.set(id, { id, kind: options.length ? "question" : "approval", text: body, createdAt: at, legacy: !text(patch.requestId) });
    }
    if (text(patch.assistantText)) state.answer = text(patch.assistantText);
    if (typeof patch.assistantDelta === "string") state.answer += patch.assistantDelta;
    for (const entry of patch.contentEntries || []) if (entry?.kind === "answer" && text(entry.text)) state.answer = entry.text;
    if (patch.completion === "cancelled") state.terminal = true;
    if (["done", "error"].includes(patch.completion) && !state.terminal) {
      const body = patch.completion === "error" ? text(patch.summary) || text(patch.errorText) : text(patch.completionText) || state.answer;
      const run = identity(patch.turnId) || state.run || `result:${fingerprint(body)}`;
      state.voiceEvent = { id: `${sessionId}:${run}:${patch.completion}`, kind: patch.completion, text: body, createdAt: at, replay: patch.replay === true };
      state.terminal = true;
    }
    if (patch.completion && (explicitRun || state.run)) state.completedRuns.add(explicitRun || state.run);
    return { voiceEvent: state.voiceEvent, pendingRequests: [...state.requests.values()] };
  }
}
