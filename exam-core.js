(function (root) {
  const questions = [
    { title: '下列哪一项是计算机的输入设备？', options: ['显示器', '键盘', '打印机', '音箱'], answer: 1, explanation: '键盘用于向计算机输入文字和指令，属于输入设备。显示器、打印机和音箱都属于输出设备。' },
    { title: '计算机中，1 字节（Byte）等于多少位（bit）？', options: ['2 位', '4 位', '8 位', '16 位'], answer: 2, explanation: '1 字节（Byte）由 8 个二进制位（bit）组成。字节是常用的数据存储单位。' },
    { title: '以下哪种软件属于操作系统？', options: ['Microsoft Word', 'Google Chrome', 'Adobe Photoshop', 'Windows'], answer: 3, explanation: 'Windows 是操作系统，负责管理硬件资源并为应用程序提供运行环境。其他三个选项属于应用软件。' },
    { title: '在网页中，HTML 的主要作用是什么？', options: ['定义内容与结构', '设置页面的视觉样式', '存储数据库记录', '加密网络连接'], answer: 0, explanation: 'HTML 用于定义网页的内容与结构，例如标题、段落和链接；CSS 主要负责视觉样式。' },
    { title: '下列哪种做法有助于保护账号安全？', options: ['所有账号使用同一个密码', '将验证码告诉陌生人', '开启双重验证', '在公共电脑上保存密码'], answer: 2, explanation: '双重验证在密码之外增加一层身份验证，可以降低密码泄露后账号被盗的风险。' }
  ];
  function remainingSeconds(deadline, now = Date.now()) { return Math.max(0, Math.ceil((deadline - now) / 1000)); }
  function grade(answers) { const correct = questions.filter((q, i) => answers[i] === q.answer).length; return { correct, score: correct * 20, unanswered: questions.filter((_, i) => answers[i] === null).length }; }
  function validState(state) {
    return state && state.version === 1 && ['running', 'finished'].includes(state.phase) && [60, 300, 600].includes(state.duration)
      && Number.isFinite(state.startedAt) && Number.isFinite(state.deadline) && state.deadline === state.startedAt + state.duration * 1000
      && Number.isInteger(state.current) && state.current >= 0 && state.current < questions.length
      && Array.isArray(state.answers) && state.answers.length === questions.length && state.answers.every(a => a === null || (Number.isInteger(a) && a >= 0 && a < 4))
      && Array.isArray(state.flags) && state.flags.length === questions.length && state.flags.every(f => typeof f === 'boolean')
      && (state.phase !== 'finished' || (Number.isFinite(state.finishedAt) && state.finishedAt >= state.startedAt && state.finishedAt <= state.deadline));
  }
  const api = { questions, remainingSeconds, grade, validState };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ExamCore = api;
})(globalThis);
