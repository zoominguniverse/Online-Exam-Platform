const { request, currentUserId, currentEpoch, hasSession, clearSession } = require('../../api')

function sameAnswers(left, right) {
  const ordered = value => JSON.stringify(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)))
  return ordered(left) === ordered(right)
}

Page({
  data: { id: '', questions: [], answers: {}, status: '正在加载', error: '', server: null, editable: true },
  onLoad({ id }) {
    this.setData({ id }); this.revision = 0; this.baseVersion = 0; this.unknown = false; this.busy = false; this.dead = false
    if (wx.onNetworkStatusChange) {
      this.network = ({ isConnected }) => { if (isConnected && this.active() && this.data.status === '保存失败，待重试') this.save() }
      wx.onNetworkStatusChange(this.network)
    }
    this.load()
  },
  onUnload() { this.dead = true; if (this.timer) clearTimeout(this.timer); if (this.network && wx.offNetworkStatusChange) wx.offNetworkStatusChange(this.network) },
  key() { return 'exam-draft:' + this.owner + ':' + this.data.id },
  active() { return !this.dead && hasSession() && currentUserId() === this.owner && currentEpoch() === this.epoch },
  persist() { if (wx.setStorageSync) wx.setStorageSync(this.key(), { baseVersion: this.baseVersion, revision: this.revision, answers: this.data.answers }) },
  render() {
    const questions = (this.data.server?.questions || []).map(question => {
      const answer = this.data.answers[question.questionId] || {}
      const options = question.type === 'TRUE_FALSE' ? [{ id: 'true', text: '正确' }, { id: 'false', text: '错误' }] : question.options
      return { ...question, options: options.map(option => ({ ...option,
        checked: answer.optionId === option.id || (answer.optionIds || []).includes(option.id) })),
        currentText: question.type === 'GAP' ? (answer.texts || []).join('\n') : answer.text || '' }
    })
    this.setData({ questions, editable: !this.data.status.startsWith('已截止') && !this.data.status.startsWith('版本冲突') })
  },
  async load() {
    this.owner = currentUserId(); this.epoch = currentEpoch()
    if (!this.active()) return
    try {
      const server = await request('GET', '/me/attempts/' + this.data.id)
      if (!this.active()) return
      this.baseVersion = server.version
      const confirmed = Object.fromEntries(server.savedAnswers.map(item => [item.questionId, item.answer]))
      const draft = wx.getStorageSync && wx.getStorageSync(this.key())
      if (draft && draft.answers) {
        this.revision = draft.revision
        this.baseVersion = draft.baseVersion
      }
      const answers = draft && draft.answers ? draft.answers : confirmed
      const status = new Date(server.serverNow) >= new Date(server.deadlineAt) || server.status !== 'IN_PROGRESS'
        ? '已截止，未同步内容未计入服务器答案' : draft && draft.baseVersion !== server.version
          ? '版本冲突，请核对服务器答案；本地草稿已保留' : draft ? '待同步' : '已保存'
      this.setData({ server, answers, status }); this.render()
      if (status === '待同步') this.schedule()
    } catch (error) {
      if (!this.active()) return
      if (error.status === 401) { clearSession(); wx.reLaunch({ url: '/pages/auth/index' }) }
      else this.setData({ error: error.message, status: '加载失败' })
    }
  },
  edit({ currentTarget, detail }) {
    if (!this.active() || this.data.status.startsWith('已截止') || this.data.status.startsWith('版本冲突')) return
    const { id, type } = currentTarget.dataset
    const answer = type === 'SINGLE' || type === 'TRUE_FALSE' ? { optionId: detail.value }
      : type === 'MULTIPLE' ? { optionIds: detail.value }
        : type === 'GAP' ? { texts: detail.value.split('\n') } : { text: detail.value }
    this.revision++
    this.setData({ answers: { ...this.data.answers, [id]: answer }, status: '待同步' })
    this.render()
    this.persist(); this.schedule()
  },
  clear({ currentTarget }) {
    const answers = { ...this.data.answers }
    delete answers[currentTarget.dataset.id]
    this.revision++
    this.setData({ answers, status: '待同步' })
    this.render()
    this.persist(); this.schedule()
  },
  schedule() {
    if (this.timer) clearTimeout(this.timer)
    this.timer = setTimeout(() => this.save(), 500)
  },
  async save() {
    if (this.busy || !this.active() || !this.data.server || this.data.status.startsWith('版本冲突')
        || this.data.status.startsWith('已截止')) return
    this.busy = true
    const revision = this.revision
    const answers = JSON.parse(JSON.stringify(this.data.answers))
    this.setData({ status: '保存中' })
    try {
      if (this.unknown) {
        const latest = await request('GET', '/me/attempts/' + this.data.id)
        if (!this.active()) return
        if (new Date(latest.serverNow) >= new Date(latest.deadlineAt) || latest.status !== 'IN_PROGRESS') {
          this.setData({ status: '已截止，未同步内容未计入服务器答案', server: latest }); return
        }
        if (latest.version !== this.baseVersion) {
          const remote = Object.fromEntries(latest.savedAnswers.map(item => [item.questionId, item.answer]))
          if (sameAnswers(remote, answers)) {
            this.baseVersion = latest.version
            this.unknown = false
            if (revision === this.revision) {
              if (wx.removeStorageSync) wx.removeStorageSync(this.key())
              this.setData({ status: '已保存', server: latest })
            } else { this.persist(); this.setData({ status: '待同步', server: latest }); this.schedule() }
            return
          }
          this.setData({ status: '版本冲突，请核对服务器答案；本地草稿已保留', server: latest }); return
        }
        this.unknown = false
      }
      const answerItems = Object.entries(answers).map(([questionId, answer]) => ({ questionId, answer }))
      const result = await request('PUT', '/me/attempts/' + this.data.id + '/answers',
        { expectedVersion: this.baseVersion, answerItems })
      if (!this.active()) return
      this.baseVersion = result.version
      this.unknown = false
      if (revision === this.revision) {
        if (wx.removeStorageSync) wx.removeStorageSync(this.key())
        this.setData({ status: '已保存', server: result })
      } else { this.persist(); this.setData({ status: '待同步', server: result }); this.schedule() }
    } catch (error) {
      if (!this.active()) return
      if (error.status === 401) { clearSession(); wx.reLaunch({ url: '/pages/auth/index' }) }
      else if (error.code === 'VERSION_CONFLICT') {
        this.setData({ status: '版本冲突，请核对服务器答案；本地草稿已保留' })
        try { const server = await request('GET', '/me/attempts/' + this.data.id); if (this.active()) this.setData({ server }) } catch (_) {}
      } else if (error.code === 'EXAM_CLOSED') this.setData({ status: '已截止，未同步内容未计入服务器答案' })
      else { this.unknown = true; this.persist(); this.setData({ status: '保存失败，待重试', error: error.message }) }
    } finally { this.busy = false }
  },
  retry() { this.save() },
})
