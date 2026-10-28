const test = require('node:test');
const assert = require('node:assert/strict');
const { questions, remainingSeconds, grade, validState } = require('../exam-core.js');

test('倒计时根据截止时间计算，切换后台后不累积误差且不会小于零', () => {
  assert.equal(remainingSeconds(61000, 1000), 60);
  assert.equal(remainingSeconds(61000, 60001), 1);
  assert.equal(remainingSeconds(61000, 61000), 0);
  assert.equal(remainingSeconds(61000, 99000), 0);
});
test('全对、部分正确和未答均正确计分', () => {
  assert.deepEqual(grade(questions.map(q => q.answer)), { correct: 5, score: 100, unanswered: 0 });
  assert.deepEqual(grade([1, null, 0, null, 2]), { correct: 2, score: 40, unanswered: 2 });
  assert.deepEqual(grade(Array(5).fill(null)), { correct: 0, score: 0, unanswered: 5 });
});
test('仅恢复结构完整且时间有效的存档', () => {
  const state = { version: 1, phase: 'running', duration: 60, startedAt: 1000, deadline: 61000, current: 0, answers: Array(5).fill(null), flags: Array(5).fill(false) };
  assert.ok(validState(state));
  assert.ok(validState({ ...state, phase: 'finished', finishedAt: 61000 }));
  for (const patch of [{ current: -1 }, { answers: [1] }, { answers: [0, 1, 2, 3, 4] }, { deadline: 0 }, { flags: [] }, { phase: 'finished' }, { version: 2 }]) assert.ok(!validState({ ...state, ...patch }));
  assert.ok(!validState(null));
});
