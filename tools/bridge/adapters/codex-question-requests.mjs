import { questionRequests } from './speech-facts.mjs';

export const REARCUE_QUESTION_TOOL = {
  type: 'function', name: 'rearcue_choice_question', deferLoading: false,
  description: 'Ask the owner a concrete choice on the RearCue phone rear display and wait for the complete answer group. Use only when the main task needs an owner decision. Internal subagents must return decisions to the parent instead. This tool does not approve file edits or commands. Answers contain the original option labels.',
  inputSchema: { type:'object', additionalProperties:false, required:['questions'], properties:{ questions:{ type:'array', minItems:1, maxItems:8, items:{ type:'object', additionalProperties:false, required:['id','question','options','multiSelect'], properties:{ id:{type:'string'}, question:{type:'string'}, multiSelect:{type:'boolean'}, options:{type:'array',minItems:1,maxItems:8,items:{type:'object',additionalProperties:false,required:['label','description'],properties:{label:{type:'string'},description:{type:'string'}}}} } } } } },
};

/** Only replies to server requests owned by this bridge. No Desktop steering or turn/start. */
export class CodexQuestionRequests {
  constructor({ write, emit, currentTurn = () => null, timeoutMs = 5000 }) {
    Object.assign(this, { write, emit, currentTurn, timeoutMs });
    this.groups = new Map();
    this.finished = new Map();
    this.submissions = new Map();
  }
  get size() { return this.groups.size; }
  register(id, params, mode = 'answers') {
    const threadId = params.threadId || params.conversationId;
    if (!threadId || !params.turnId || !Array.isArray(params.questions)) return [];
    const prefix = params.itemId || String(id);
    const groupId = JSON.stringify([params.turnId, id]);
    if (this.groups.has(groupId)) return this.groups.get(groupId).requests;
    const validIds = params.questions.every((q) => q && typeof q.id === "string" && q.id) && new Set(params.questions.map((q) => q.id)).size === params.questions.length;
    const requests = questionRequests(prefix, params.questions).map((request) => {
      const index = Number(request.id.slice(prefix.length + 1));
      const raw = params.questions[index];
      const optionValues = (Array.isArray(raw.options) ? raw.options : []).map((o) => typeof o === 'string' ? o : o?.label).filter((v) => typeof v === 'string' && v.trim());
      const multiple = raw.isMultiSelect === true || raw.multiSelect === true;
      return { ...request, groupId, turnId: params.turnId, nativeId: raw.id, optionValues, multiple,
        canAnswer: validIds && optionValues.length > 0 && optionValues.length === request.options.length };
    });
    this.groups.set(groupId, { id, groupId, threadId, turnId: params.turnId, itemId: prefix, requests, mode, written: false, finish: null });
    return requests;
  }
  pending(threadId) { return [...this.groups.values()].filter((g) => g.threadId === threadId).flatMap((g) => g.requests); }
  metadata(threadId, questionId) {
    for (const group of this.groups.values()) {
      if (group.threadId !== threadId) continue;
      const question = group.requests.find((q) => q.id === questionId);
      if (question) {
        const { groupId, turnId, optionValues, multiple, canAnswer } = question;
        return { groupId, turnId, optionValues, multiple, canAnswer };
      }
    }
    return {};
  }
  state(threadId, groupId) {
    const group = this.groups.get(groupId);
    return { receipt: group?.threadId === threadId ? (group.written ? 'unknown' : 'pending') :
      this.finished.get(threadId + '\0' + groupId) || 'expired' };
  }
  async submit({ sessionId, groupId, turnId, requestId, answers }) {
    if (typeof requestId !== 'string' || !requestId || requestId.length > 160) return { receipt: 'bad-request' };
    const signature = JSON.stringify({ sessionId, groupId, turnId, answers });
    const previous = this.submissions.get(requestId);
    if (previous) return previous.signature === signature ? previous.promise : { receipt: 'bad-request' };
    const group = this.groups.get(groupId);
    if (!group || group.threadId !== sessionId || group.turnId !== turnId) return { receipt: 'expired' };
    const active = this.currentTurn(sessionId);
    if (active && active !== turnId) return { receipt: 'expired' };
    if (group.written) return { receipt: 'unknown' };
    if (!group.requests.length || group.requests.some((q) => !q.canAnswer)) return { receipt: 'unsupported' };
    if (!answers || typeof answers !== 'object' || Array.isArray(answers) ||
        Object.keys(answers).length !== group.requests.length) return { receipt: 'bad-request' };
    const nativeAnswers = Object.create(null);
    for (const question of group.requests) {
      const chosen = answers[question.id];
      if (!Array.isArray(chosen) || !chosen.length || (!question.multiple && chosen.length !== 1) ||
          new Set(chosen).size !== chosen.length || chosen.some((v) => !question.optionValues.includes(v))) return { receipt: 'bad-request' };
      nativeAnswers[question.nativeId] = { answers: chosen };
    }
    const promise = new Promise((resolve) => {
      const timer = setTimeout(() => resolve({ receipt: 'unknown' }), this.timeoutMs);
      group.finish = (receipt) => { clearTimeout(timer); resolve({ receipt }); };
      // Mark before writing; a timeout or lost HTTP response never causes another native reply.
      group.written = true;
      group.resultText = JSON.stringify({ answers: nativeAnswers });
      const result = group.mode === 'dynamic' ? { success:true, contentItems:[{type:'inputText',text:group.resultText}] } : { answers: nativeAnswers };
      try { this.write(JSON.stringify({ id: group.id, result }) + '\n',
        (error) => { if (error) group.finish('unknown'); }); }
      catch { group.finish('unknown'); }
    });
    this.submissions.set(requestId, { signature, promise });
    while (this.submissions.size > 500) this.submissions.delete(this.submissions.keys().next().value);
    return promise;
  }
  resolved(id, threadId) {
    for (const group of [...this.groups.values()]) {
      if (String(group.id) === String(id) && (!threadId || group.threadId === threadId)) {
        if (group.mode !== 'dynamic' || !group.written) this.remove(group, group.written ? 'accepted' : 'expired');
      }
    }
  }
  completed(threadId, item) {
    for (const group of [...this.groups.values()]) {
      if (group.mode !== 'dynamic' || group.threadId !== threadId || group.itemId !== item?.id) continue;
      if (item.success === true && group.written && (item.contentItems || []).some((c) => c.type === 'inputText' && c.text === group.resultText)) this.remove(group, 'accepted');
      else if (item.success === false || item.status === 'failed') this.remove(group, 'failed');
    }
  }
  clear(threadId = null, turnId = null) {
    for (const group of [...this.groups.values()]) {
      if ((!threadId || group.threadId === threadId) && (!turnId || group.turnId === turnId)) this.remove(group, 'expired');
    }
  }
  remove(group, receipt) {
    this.groups.delete(group.groupId);
    this.finished.set(group.threadId + '\0' + group.groupId, receipt);
    while (this.finished.size > 500) this.finished.delete(this.finished.keys().next().value);
    group.finish?.(receipt);
    this.emit({ sessionId: group.threadId, source: 'codex', status: 'working',
      resolvedRequestIds: group.requests.map((q) => q.id) });
  }
}
