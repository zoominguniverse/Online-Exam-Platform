(() => {
  const { questions, remainingSeconds, grade, validState } = ExamCore;
  const $ = id => document.getElementById(id);
  const key = 'zhixu-exam-v1';
  const letters = ['A', 'B', 'C', 'D'];
  let storageAvailable = true;
  let state = freshState();
  function freshState() { return { version: 1, phase: 'ready', duration: 600, current: 0, answers: Array(5).fill(null), flags: Array(5).fill(false) }; }
  try { const saved = JSON.parse(localStorage.getItem(key)); if (validState(saved)) state = saved; } catch { storageAvailable = false; }
  function save() { try { localStorage.setItem(key, JSON.stringify(state)); } catch { storageAvailable = false; } }
  function timeLabel(seconds) { return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`; }
  function announce(text) { $('announcement').textContent = text; }
  function start() {
    state.duration = Number($('duration').value) * 60;
    state.startedAt = Date.now(); state.deadline = state.startedAt + state.duration * 1000; state.phase = 'running';
    save(); render(); announce('考试开始，祝你答题顺利。');
  }
  function finish(automatic = false) {
    if (state.phase !== 'running') return;
    state.phase = 'finished'; state.automatic = automatic; state.finishedAt = Math.min(Date.now(), state.deadline);
    $('confirm-dialog').close(); save(); render();
    announce(automatic ? '时间已到，试卷已自动提交。' : '交卷成功，成绩已生成。');
  }
  function ensureTime() { if (state.phase === 'running' && remainingSeconds(state.deadline) === 0) { finish(true); return false; } return true; }
  function tick() {
    if (!ensureTime()) return;
    const left = state.phase === 'ready' ? state.duration : remainingSeconds(state.deadline, state.phase === 'finished' ? state.finishedAt : Date.now());
    $('timer').textContent = timeLabel(left);
    $('timer').classList.toggle('urgent', state.phase === 'running' && left <= 60);
    $('timer-progress').style.width = `${left / state.duration * 100}%`;
  }
  function render() {
    const answered = state.answers.filter(a => a !== null).length;
    const finished = state.phase === 'finished';
    $('status').textContent = { ready: '未开始', running: '进行中', finished: '已完成' }[state.phase];
    $('timer-note').textContent = { ready: '准备好后，开始你的练习', running: '保持专注，你正在稳步前进', finished: state.automatic ? '时间已到，已自动交卷' : '本次练习已完成' }[state.phase];
    $('duration-control').hidden = state.phase !== 'ready';
    $('answer-count').textContent = `${answered} / 5`;
    $('progress-label').textContent = `${answered * 20}%`;
    $('answer-progress').style.width = `${answered * 20}%`;
    $('submit').innerHTML = state.phase === 'ready' ? '开始考试 <span>→</span>' : finished ? '再练一次 <span>↻</span>' : '提交试卷 <span>→</span>';
    $('submit-note').textContent = !storageAvailable ? '浏览器存储不可用，请保持此页面打开' : state.phase === 'ready' ? '开始后计时，到时自动交卷' : finished ? '再练一次会开始一份新的答卷' : '进度已自动保存，可放心刷新';
    $('answer-grid').innerHTML = questions.map((q, i) => `<button class="answer-number ${state.answers[i] !== null ? 'done' : ''} ${state.current === i ? 'current' : ''} ${state.flags[i] ? 'flagged' : ''} ${finished ? (state.answers[i] === q.answer ? 'correct' : 'incorrect') : ''}" data-index="${i}" aria-label="第 ${i + 1} 题，${state.answers[i] === null ? '未答' : '已答'}${state.flags[i] ? '，已标记' : ''}" ${state.current === i ? 'aria-current="step"' : ''}>${i + 1}</button>`).join('');
    const q = questions[state.current];
    const result = grade(state.answers);
    $('question-panel').innerHTML = `${finished ? `<div class="result-banner"><div><span class="eyebrow">${state.automatic ? '时间到 · 自动交卷' : '练习完成 · 继续进步'}</span><h2>${result.score >= 80 ? '做得不错，继续保持！' : '每一次练习，都有新收获'}</h2><p>答对 ${result.correct} / 5 题 · 未答 ${result.unanswered} 题 · 用时 ${timeLabel(Math.ceil((state.finishedAt - state.startedAt) / 1000))}</p></div><div class="score">${result.score}<span> / 100 分</span></div></div>` : ''}
      <div class="question-top"><div><span class="question-type">单选题</span><span class="question-index">第 ${state.current + 1} 题 / 共 5 题</span></div><button id="flag" class="flag-button ${state.flags[state.current] ? 'is-flagged' : ''}" ${state.phase !== 'running' ? 'disabled' : ''} aria-pressed="${state.flags[state.current]}">⚑ ${state.flags[state.current] ? '已标记' : '标记此题'}</button></div>
      <h2 id="question-title" tabindex="-1">${q.title}</h2><p class="question-hint">请选择一个正确答案<span>20 分</span></p>
      <div class="options" role="radiogroup" aria-labelledby="question-title">${q.options.map((option, i) => `<label class="option ${state.answers[state.current] === i ? 'selected' : ''} ${finished && q.answer === i ? 'right-answer' : ''} ${finished && state.answers[state.current] === i && q.answer !== i ? 'wrong-answer' : ''}"><input type="radio" name="answer" value="${i}" ${state.answers[state.current] === i ? 'checked' : ''} ${state.phase !== 'running' ? 'disabled' : ''}><span class="option-letter">${letters[i]}</span><span class="option-text">${option}</span><span class="option-check" aria-hidden="true">${finished && q.answer === i ? '✓' : state.answers[state.current] === i ? '●' : ''}</span></label>`).join('')}</div>
      ${finished ? `<div class="explanation"><strong>答案解析 · 正确答案 ${letters[q.answer]}</strong><p>${q.explanation}</p></div>` : state.phase === 'ready' ? '<div class="ready-notice">点击右侧「开始考试」后即可答题，准备好了就出发吧。</div>' : '<div class="autosave">✓ 选择答案后自动保存</div>'}
      <div class="question-bottom"><button class="button secondary" id="previous" ${state.current === 0 ? 'disabled' : ''}>← 上一题</button><span>${String(state.current + 1).padStart(2, '0')} <span>/ 05</span></span><button class="button secondary next" id="next" ${state.current === 4 ? 'disabled' : ''}>下一题 →</button></div>`;
    $('previous').onclick = () => navigate(state.current - 1);
    $('next').onclick = () => navigate(state.current + 1);
    $('flag').onclick = () => { if (!ensureTime() || state.phase !== 'running') return; state.flags[state.current] = !state.flags[state.current]; save(); render(); $('flag').focus(); };
    document.querySelectorAll('input[name="answer"]').forEach(input => input.onchange = () => {
      if (!ensureTime() || state.phase !== 'running') return;
      state.answers[state.current] = Number(input.value); save(); render();
      document.querySelector(`input[name="answer"][value="${input.value}"]`).focus();
    });
    tick();
  }
  function navigate(index) { if (!ensureTime() || index < 0 || index >= questions.length) return; state.current = index; if (state.phase !== 'ready') save(); render(); $('question-title').focus(); }
  $('answer-grid').onclick = event => { const button = event.target.closest('[data-index]'); if (button) navigate(Number(button.dataset.index)); };
  $('duration').onchange = () => { state.duration = Number($('duration').value) * 60; tick(); };
  $('submit').onclick = () => {
    if (!ensureTime()) return;
    if (state.phase === 'ready') { start(); return; }
    if (state.phase === 'finished') { state = freshState(); $('duration').value = '10'; try { localStorage.removeItem(key); } catch {} render(); return; }
    const unanswered = state.answers.filter(a => a === null).length;
    $('confirm-message').textContent = unanswered ? `还有 ${unanswered} 道题未作答，未答题将计为 0 分。确认提交本次试卷吗？` : '你已完成全部 5 道题，提交后即可查看成绩和答案解析。';
    $('confirm-dialog').showModal();
  };
  $('cancel-submit').onclick = () => $('confirm-dialog').close();
  $('confirm-submit').onclick = () => { if (ensureTime()) finish(); };
  document.addEventListener('visibilitychange', tick);
  window.addEventListener('pageshow', tick);
  render(); setInterval(tick, 250);
})();
