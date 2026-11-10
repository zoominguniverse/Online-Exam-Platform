<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, read, write, type Account } from '../../shared/api'

type Answer = { optionId?: string; optionIds?: string[]; texts?: string[]; text?: string }
type Question = { questionId: string; type: string; stemHtml: string; options: { id: string; text: string }[] }
type Attempt = { id: string; version: number; status: string; serverNow: string; deadlineAt: string;
  questions: Question[]; savedAnswers: { questionId: string; answer: Answer }[] }
type Draft = { baseVersion: number; revision: number; answers: Record<string, Answer> }
const props = defineProps<{ id: string }>()
const router = useRouter()
const account = ref<Account>()
const attempt = ref<Attempt>()
const answers = ref<Record<string, Answer>>({})
const state = ref<'loading' | 'pending' | 'saving' | 'saved' | 'failed' | 'conflict' | 'closed'>('loading')
const message = ref('')
let revision = 0
let baseVersion = 0
let timer: ReturnType<typeof setTimeout> | undefined
let busy = false
let uncertain = false
let stopped = false
let controller: AbortController | undefined
let draftKey = ''

function persist() {
  if (draftKey) localStorage.setItem(draftKey, JSON.stringify({ baseVersion, revision, answers: answers.value } satisfies Draft))
}

function edit(id: string, value: Answer) {
  answers.value = { ...answers.value, [id]: value }
  revision++
  state.value = 'pending'
  persist()
  schedule()
}

function schedule() {
  if (timer) clearTimeout(timer)
  if (!stopped && state.value !== 'conflict' && state.value !== 'closed') timer = setTimeout(() => void save(), 500)
}

function same(a: Record<string, Answer>, b: Record<string, Answer>) {
  const normalized = (value: Record<string, Answer>) => JSON.stringify(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)))
  return normalized(a) === normalized(b)
}

function fromServer(value: Attempt) {
  return Object.fromEntries(value.savedAnswers.map(item => [item.questionId, item.answer])) as Record<string, Answer>
}

async function save() {
  if (busy || stopped || !attempt.value || !account.value || state.value === 'conflict' || state.value === 'closed') return
  if (localStorage.getItem('exam-active-user') !== account.value.id) return
  busy = true
  controller = new AbortController()
  const generation = account.value.id
  const capturedRevision = revision
  const capturedAnswers = structuredClone(answers.value)
  state.value = 'saving'
  try {
    if (uncertain) {
      const latest = await read<Attempt>('/me/attempts/' + props.id, controller.signal)
      if (stopped || account.value?.id !== generation) return
      if (new Date(latest.serverNow) >= new Date(latest.deadlineAt) || latest.status !== 'IN_PROGRESS') {
        state.value = 'closed'; message.value = '已截止，未同步内容未计入服务器答案。'; return
      }
      if (latest.version !== baseVersion) {
        if (same(fromServer(latest), capturedAnswers)) {
          baseVersion = latest.version; attempt.value = latest; uncertain = false
          if (capturedRevision === revision) { localStorage.removeItem(draftKey); state.value = 'saved' }
          else { state.value = 'pending'; persist(); schedule() }
          return
        }
        state.value = 'conflict'; message.value = '服务器答案已变化。请保留本地草稿并核对服务器版本。'; return
      }
      uncertain = false
    }
    const answerItems = Object.entries(capturedAnswers).map(([questionId, answer]) => ({ questionId, answer }))
    if (localStorage.getItem('exam-active-user') !== generation) return
    const result = await write<Attempt>('PUT', '/me/attempts/' + props.id + '/answers',
      { expectedVersion: baseVersion, answerItems }, controller.signal)
    if (stopped || account.value?.id !== generation) return
    baseVersion = result.version
    attempt.value = result
    uncertain = false
    if (capturedRevision === revision) { localStorage.removeItem(draftKey); state.value = 'saved' }
    else { state.value = 'pending'; persist(); schedule() }
  } catch (cause) {
    if (stopped || account.value?.id !== generation) return
    const error = cause as ApiError
    if (error.status === 401) {
      localStorage.removeItem(draftKey); localStorage.removeItem('exam-active-user')
      stopped = true; void router.replace({ name: 'login' })
    } else if (error.status === 409 && error.code === 'VERSION_CONFLICT') {
      state.value = 'conflict'
      message.value = '另一端已更新。请核对服务器答案；本地草稿已保留。'
      try {
        const latest = await read<Attempt>('/me/attempts/' + props.id, controller.signal)
        if (!stopped && account.value?.id === generation) attempt.value = latest
      } catch { /* 草稿仍保留 */ }
    } else if (error.status === 409 && error.code === 'EXAM_CLOSED') {
      state.value = 'closed'; message.value = '已截止，未同步内容未计入服务器答案。'
    } else {
      uncertain = true; state.value = 'failed'; message.value = error.message
      persist()
    }
  } finally { busy = false }
}

async function load() {
  try {
    const owner = await read<Account>('/me')
    if (stopped) return
    account.value = owner
    const active = localStorage.getItem('exam-active-user')
    if (active && active !== account.value.id) {
      for (const key of Object.keys(localStorage)) if (key.startsWith(`exam-draft:${active}:`)) localStorage.removeItem(key)
    }
    localStorage.setItem('exam-active-user', account.value.id)
    draftKey = `exam-draft:${account.value.id}:${props.id}`
    const loaded = await read<Attempt>('/me/attempts/' + props.id)
    if (stopped || localStorage.getItem('exam-active-user') !== owner.id) return
    attempt.value = loaded
    baseVersion = attempt.value.version
    answers.value = fromServer(attempt.value)
    const stored = localStorage.getItem(draftKey)
    if (stored) {
      const draft = JSON.parse(stored) as Draft
      answers.value = draft.answers; revision = draft.revision
      baseVersion = draft.baseVersion
      state.value = draft.baseVersion === attempt.value.version ? 'pending' : 'conflict'
      if (state.value === 'pending') schedule()
    } else state.value = 'saved'
    if (new Date(attempt.value.serverNow) >= new Date(attempt.value.deadlineAt) || attempt.value.status !== 'IN_PROGRESS') {
      state.value = 'closed'; message.value = '已截止，未同步内容未计入服务器答案。'
    }
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 401) {
      if (draftKey) localStorage.removeItem(draftKey)
      localStorage.removeItem('exam-active-user')
      await router.replace({ name: 'login' })
    }
    else { state.value = 'failed'; message.value = (cause as Error).message }
  }
}
function online() { if (state.value === 'failed' || state.value === 'pending') void save() }
function accountChanged(event: StorageEvent) {
  if (event.key === 'exam-active-user' && event.newValue !== account.value?.id) {
    stopped = true; if (timer) clearTimeout(timer); controller?.abort()
  }
}
onMounted(() => { window.addEventListener('online', online); window.addEventListener('storage', accountChanged); void load() })
onUnmounted(() => {
  stopped = true; if (timer) clearTimeout(timer); controller?.abort()
  window.removeEventListener('online', online); window.removeEventListener('storage', accountChanged)
})
</script>

<template>
  <main>
    <h1>答卷</h1>
    <RouterLink to="/home">返回学生中心</RouterLink>
    <p v-if="attempt">截止：{{ attempt.deadlineAt }}</p>
    <p role="status">{{ { loading: '正在加载', pending: '待同步', saving: '保存中', saved: '已保存',
      failed: '保存失败，请重试', conflict: '版本冲突', closed: '已截止' }[state] }}</p>
    <p v-if="message" role="alert">{{ message }}</p>
    <button v-if="state === 'failed' || state === 'pending'" type="button" @click="save">重试同步</button>
    <section v-for="(question, index) in attempt?.questions || []" :key="question.questionId">
      <h2>第 {{ index + 1 }} 题</h2>
      <div v-html="question.stemHtml"></div>
      <template v-if="question.type === 'SINGLE' || question.type === 'TRUE_FALSE'">
        <label v-for="option in question.type === 'TRUE_FALSE' ? [{ id: 'true', text: '正确' }, { id: 'false', text: '错误' }] : question.options" :key="option.id">
          <input type="radio" :name="question.questionId" :value="option.id" :disabled="state === 'closed' || state === 'conflict'"
            :checked="answers[question.questionId]?.optionId === option.id" @change="edit(question.questionId, { optionId: option.id })">{{ option.text }}
        </label>
      </template>
      <template v-else-if="question.type === 'MULTIPLE'">
        <label v-for="option in question.options" :key="option.id">
          <input type="checkbox" :disabled="state === 'closed' || state === 'conflict'"
            :checked="answers[question.questionId]?.optionIds?.includes(option.id)"
            @change="edit(question.questionId, { optionIds: answers[question.questionId]?.optionIds?.includes(option.id)
              ? answers[question.questionId].optionIds!.filter(id => id !== option.id)
              : [...(answers[question.questionId]?.optionIds || []), option.id] })">{{ option.text }}
        </label>
      </template>
      <textarea v-else-if="question.type === 'SHORT'" :value="answers[question.questionId]?.text || ''"
        :disabled="state === 'closed' || state === 'conflict'" maxlength="10000"
        @input="edit(question.questionId, { text: ($event.target as HTMLTextAreaElement).value })"></textarea>
      <textarea v-else-if="question.type === 'GAP'" :value="answers[question.questionId]?.texts?.join('\n') || ''"
        :disabled="state === 'closed' || state === 'conflict'"
        @input="edit(question.questionId, { texts: ($event.target as HTMLTextAreaElement).value.split('\n') })"></textarea>
      <button type="button" :disabled="state === 'closed' || state === 'conflict'"
        @click="answers = Object.fromEntries(Object.entries(answers).filter(([id]) => id !== question.questionId)); revision++; state = 'pending'; persist(); schedule()">清空本题</button>
    </section>
    <p v-if="state === 'conflict'">本地草稿已保留。请在另一端核对服务器答案后再决定如何处理。</p>
  </main>
</template>
