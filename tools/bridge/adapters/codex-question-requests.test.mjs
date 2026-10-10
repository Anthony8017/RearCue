import test from 'node:test';
import assert from 'node:assert/strict';
import { CodexQuestionRequests } from './codex-question-requests.mjs';
function fixture(timeoutMs = 30) {
  const writes = [], events = []; let turn = 't1';
  const registry = new CodexQuestionRequests({ write: (s, done) => { writes.push(JSON.parse(s)); done?.(); },
    emit: (e) => events.push(e), currentTurn: () => turn, timeoutMs });
  const questions = registry.register(91, { threadId: 's1', turnId: 't1', itemId: 'ask', questions: [
    { id: 'native-a', question: '方向？', options: [{ label: '甲', description: '说明' }, { label: '乙' }] },
    { id: 'native-b', question: '范围？', options: [{ label: '一' }, { label: '二' }] },
  ] });
  const body = { sessionId: 's1', turnId: 't1', groupId: questions[0].groupId, requestId: 'request-1',
    answers: { 'ask:0': ['甲'], 'ask:1': ['二'] } };
  return { registry, questions, body, writes, events, changeTurn: (id) => { turn = id; } };
}
test('完整组按原始RPC id发送；只有原生resolved后报告提交，不发新回合', async () => {
  const f = fixture(); const result = f.registry.submit(f.body);
  assert.deepEqual(f.writes, [{ id: 91, result: { answers: { 'native-a': { answers: ['甲'] }, 'native-b': { answers: ['二'] } } } }]);
  assert.equal(f.registry.state('s1', f.body.groupId).receipt, 'unknown');
  f.registry.resolved(91, 's1'); assert.equal((await result).receipt, 'accepted');
  assert.deepEqual(f.events[0].resolvedRequestIds, ['ask:0', 'ask:1']);
  assert.equal(f.registry.state('s1', f.body.groupId).receipt, 'accepted');
});
test('不完整答案和描述文字均不得冒充选项值；单选不能多选', async () => {
  for (const answers of [{ 'ask:0': ['甲'] }, { 'ask:0': ['甲：说明'], 'ask:1': ['二'] }, { 'ask:0': ['甲', '乙'], 'ask:1': ['二'] }, { 'ask:0': ['甲'], 'ask:1': ['二'], extra: ['甲'] }]) {
    const f = fixture(); assert.equal((await f.registry.submit({ ...f.body, answers })).receipt, 'bad-request'); assert.equal(f.writes.length, 0);
  }
});
test('错误会话和已换轮不能提交；不存在的Desktop题不走steer', async () => {
  const f = fixture(); assert.equal((await f.registry.submit({ ...f.body, sessionId: 'other' })).receipt, 'expired');
  assert.equal((await f.registry.submit({ ...f.body, groupId: 'desktop-call' })).receipt, 'expired');
  f.changeTurn('t2'); assert.equal((await f.registry.submit(f.body)).receipt, 'expired'); assert.equal(f.writes.length, 0);
});
test('结果未知不重发；迟到resolved可通过只读状态确认', async () => {
  const f = fixture(5); assert.equal((await f.registry.submit(f.body)).receipt, 'unknown');
  assert.equal((await f.registry.submit(f.body)).receipt, 'unknown');
  assert.equal((await f.registry.submit({ ...f.body, requestId: 'retry' })).receipt, 'unknown');
  assert.equal(f.writes.length, 1); f.registry.resolved(91, 's1'); assert.equal(f.registry.state('s1', f.body.groupId).receipt, 'accepted');
});
test('取消或退出解除整组；同requestId换载荷不再发送', async () => {
  const f = fixture(); const response = f.registry.submit(f.body);
  assert.equal((await f.registry.submit({ ...f.body, answers: { 'ask:0': ['乙'], 'ask:1': ['一'] } })).receipt, 'bad-request');
  f.registry.clear('s1', 't1'); assert.equal((await response).receipt, 'expired'); assert.equal(f.writes.length, 1);
});
test('无选项或坏题目ID明确不支持；多个组互不解除', async () => {
  const f = fixture(); f.registry.register(92, { threadId: 's1', turnId: 't1', itemId: 'other', questions: [{ id: 'free', question: '自由答复' }] });
  const other = f.registry.pending('s1').find((q) => q.id === 'other:0'); assert.equal(other.canAnswer, false);
  assert.equal((await f.registry.submit({ ...f.body, groupId: other.groupId, answers: { 'other:0': ['x'] } })).receipt, 'unsupported');
  f.registry.resolved(92, 's1'); assert.equal(f.registry.pending('s1').length, 2);
});
test('重复请求不重置已发送状态；显式多选按原题值回传', async () => {
  const f = fixture(5); await f.registry.submit(f.body);
  f.registry.register(91, { threadId: 's1', turnId: 't1', itemId: 'ask', questions: [] });
  assert.equal(f.registry.state('s1', f.body.groupId).receipt, 'unknown');
  const multi = f.registry.register(99, { threadId: 's2', turnId: 't1', itemId: 'multi', questions: [{ id: 'q', question: '多选', isMultiSelect: true, options: ['A','B'] }] });
  const promise = f.registry.submit({ sessionId: 's2', turnId: 't1', groupId: multi[0].groupId, requestId: 'm', answers: { 'multi:0': ['A','B'] } });
  f.registry.resolved(99, 's2'); assert.equal((await promise).receipt, 'accepted');
});


test('手机动态选项工具回原请求，等待工具完成证据，不把单纯解除当答案成功', async () => {
  const f = fixture(); const requests = f.registry.register(110, {threadId:'s1',turnId:'t1',itemId:'dynamic',questions:[{id:'q',question:'选择',options:['A','B']}]},'dynamic');
  const reply = f.registry.submit({sessionId:'s1',groupId:requests[0].groupId,turnId:'t1',requestId:'dynamic-reply',answers:{'dynamic:0':['A']}});
  const written = f.writes.at(-1); assert.equal(written.id,110); assert.equal(written.result.success,true);
  assert.equal(written.result.contentItems[0].type,'inputText');
  f.registry.resolved(110,'s1'); assert.equal(f.registry.state('s1',requests[0].groupId).receipt,'unknown');
  f.registry.completed('s1',{id:'dynamic',success:true,status:'completed',contentItems:written.result.contentItems});
  assert.equal((await reply).receipt,'accepted');
});
