<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, operation, read, write, type Account } from '../../shared/api'
import { answered, type Answer, type Question, type Attempt } from '../../shared/exam'
import RichContent from '../../shared/RichContent.vue'

type Draft = { baseVersion: number; revision: number; answers: Record<string, Answer>;
  unconfirmed?: Record<string, Answer> }
const props = defineProps<{ id: string }>()
const router = useRouter()
const account = ref<Account>()
const attempt = ref<Attempt>()
const answers = ref<Record<string, Answer>>({})
const state = ref<'loading' | 'pending' | 'saving' | 'saved' | 'failed' | 'conflict' | 'closed'>('loading')
const message = ref('')
const submitting=ref(false)
const confirming=ref(false)
const marked=ref<string[]>([])
const selected=ref('')
const remaining=ref<number>(Infinity)
const editable=computed(()=>!submitting.value&&!submission&&state.value!=='closed'&&state.value!=='conflict')
const unanswered=computed(()=>attempt.value?.questions.filter(question=>!answered(answers.value[question.questionId])).length || 0)
const remainingText=computed(()=>Number.isFinite(remaining.value)?`${Math.floor(Math.ceil(remaining.value/1000)/60)}:${String(Math.ceil(remaining.value/1000)%60).padStart(2,'0')}`:'正在同步')
let serverRemaining=Infinity
let sampledAt=0
let closing=false
let clockTimer:ReturnType<typeof setInterval>|undefined
let syncTimer:ReturnType<typeof setInterval>|undefined
let submission:{key:string;body:object}|undefined
let revision = 0
let baseVersion = 0
let timer: ReturnType<typeof setTimeout> | undefined
let busy = false
let unconfirmed: Record<string, Answer> | undefined
let stopped = false
let controller: AbortController | undefined
let draftKey = ''

function persist() {
  if (draftKey) localStorage.setItem(draftKey, JSON.stringify({ baseVersion, revision, answers: answers.value, unconfirmed } satisfies Draft))
}

function edit(id: string, value: Answer) {
  if(!editable.value) return
  answers.value = { ...answers.value, [id]: value }
  if (value.optionIds?.length === 0) delete answers.value[id]
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
  if (busy || stopped || submission || !attempt.value || !account.value || state.value === 'conflict' || state.value === 'closed') return
  if (localStorage.getItem('exam-active-user') !== account.value.id) return
  busy = true
  controller = new AbortController()
  const generation = account.value.id
  const capturedRevision = revision
  const capturedAnswers: Record<string, Answer> = JSON.parse(JSON.stringify(answers.value))
  state.value = 'saving'
  message.value = ''
  try {
    if (unconfirmed) {
      const latest = await read<Attempt>('/me/attempts/' + props.id, controller.signal)
      if (!active(generation)) return
      attempt.value = latest
      if (new Date(latest.serverNow) >= new Date(latest.deadlineAt) || latest.status !== 'IN_PROGRESS') {
        state.value = 'closed'; message.value = '已截止，未同步内容未计入服务器答案。'; await resultIfClosed(latest); return
      }
      if (latest.version !== baseVersion) {
        if (same(fromServer(latest), unconfirmed)) {
          baseVersion = latest.version; attempt.value = latest; unconfirmed = undefined
          if (same(fromServer(latest), capturedAnswers)) {
            if (capturedRevision === revision) { localStorage.removeItem(draftKey); state.value = 'saved' }
            else { state.value = 'pending'; persist(); schedule() }
            return
          }
        } else {
          attempt.value = latest
          state.value = 'conflict'; message.value = '服务器答案已变化。请核对双方内容后决定是否加载服务器版本。'; return
        }
      }
      unconfirmed = undefined
    }
    const answerItems = Object.entries(capturedAnswers).map(([questionId, answer]) => ({ questionId, answer }))
    if (localStorage.getItem('exam-active-user') !== generation) return
    unconfirmed = capturedAnswers
    persist()
    const result = await write<Attempt>('PUT', '/me/attempts/' + props.id + '/answers',
      { expectedVersion: baseVersion, answerItems }, controller.signal)
    if (!active(generation)) return
    baseVersion = result.version
    attempt.value = result
    unconfirmed = undefined
    if(await resultIfClosed(result)) return
    if (capturedRevision === revision) { localStorage.removeItem(draftKey); state.value = 'saved' }
    else { state.value = 'pending'; persist(); schedule() }
  } catch (cause) {
    if (!active(generation)) return
    const error = cause as ApiError
    if(closing) return
    if (error.status === 401) {
      localStorage.removeItem(draftKey); localStorage.removeItem('exam-active-user')
      stopped = true; void router.replace({ name: 'login' })
    } else if (error.status === 409 && error.code === 'VERSION_CONFLICT') {
      state.value = 'conflict'
      unconfirmed = undefined
      persist()
      message.value = '另一端已更新。请核对服务器答案；本地草稿已保留。'
      try {
        const latest = await read<Attempt>('/me/attempts/' + props.id, controller.signal)
        if (active(generation)) attempt.value = latest
      } catch { /* 草稿仍保留 */ }
    } else if (error.status === 409 && error.code === 'EXAM_CLOSED') {
      state.value = 'closed'; message.value = '已截止，未同步内容未计入服务器答案。'
      void closeAndRead()
    } else {
      state.value = 'failed'; message.value = error.message
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
      unconfirmed = draft.unconfirmed
      state.value = draft.baseVersion === attempt.value.version || unconfirmed ? 'pending' : 'conflict'
      if (state.value === 'pending') schedule()
    } else state.value = 'saved'
    if (new Date(attempt.value.serverNow) >= new Date(attempt.value.deadlineAt) || attempt.value.status !== 'IN_PROGRESS') {
      state.value = 'closed'; message.value = '已截止，未同步内容未计入服务器答案。'
      await resultIfClosed(attempt.value)
    }
  } catch (cause) {
    if (stopped) return
    if (cause instanceof ApiError && cause.status === 401) {
      if (draftKey) localStorage.removeItem(draftKey)
      localStorage.removeItem('exam-active-user')
      await router.replace({ name: 'login' })
    }
    else { state.value = 'failed'; message.value = (cause as Error).message }
  }
}
function online() { if (state.value === 'closed') void closeAndRead(); else if (state.value === 'failed' || state.value === 'pending') void save() }
function active(owner: string) {
  return !stopped && account.value?.id === owner && localStorage.getItem('exam-active-user') === owner
}
function serverAnswer(question: Question) {
  const value = attempt.value?.savedAnswers.find(item => item.questionId === question.questionId)?.answer
  if (!value) return '未作答'
  if (value.text !== undefined) return value.text || '未作答'
  if (value.texts) return value.texts.join('；') || '未作答'
  const ids = value.optionIds || [value.optionId]
  return ids.map(id => question.type === 'TRUE_FALSE' ? (id === 'true' ? '正确' : '错误')
    : question.options.find(option => option.id === id)?.text || id).join('、')
}
async function useServerAnswers() {
  if (busy || !account.value) return
  const owner = account.value.id
  busy = true
  try {
    const latest = await read<Attempt>('/me/attempts/' + props.id)
    if (!active(owner)) return
    attempt.value = latest
    answers.value = fromServer(latest)
    baseVersion = latest.version
    unconfirmed = undefined
    localStorage.removeItem(draftKey)
    message.value = ''
    state.value = new Date(latest.serverNow) >= new Date(latest.deadlineAt)
      || latest.status !== 'IN_PROGRESS' ? 'closed' : 'saved'
    await resultIfClosed(latest)
  } catch (cause) {
    if (active(owner)) message.value = '未能加载服务器答案，本地草稿仍保留。' + (cause as Error).message
  } finally { busy = false }
}
function accountChanged(event: StorageEvent) {
  if (event.key === 'exam-active-user' && event.newValue !== account.value?.id) {
    stopped = true; if (timer) clearTimeout(timer); controller?.abort()
    answers.value = {}; attempt.value = undefined; account.value = undefined
    void router.replace({ name: 'login' })
  }
}
watch(attempt,value=>{if(value){serverRemaining=new Date(value.deadlineAt).getTime()-new Date(value.serverNow).getTime();sampledAt=performance.now();remaining.value=Math.max(0,serverRemaining)}})
async function resultIfClosed(value:Attempt) {
  if(value.status==='IN_PROGRESS') return false
  const unsynced=!same(fromServer(value),answers.value)
  state.value='closed'
  if(timer) clearTimeout(timer)
  if(!unsynced&&draftKey) localStorage.removeItem(draftKey)
  await router.replace({path:'/attempts/'+props.id+'/review',query:unsynced?{unsynced:'1'}:{}})
  return true
}
async function closeAndRead() {
  if(closing||stopped||!account.value) return
  const owner=account.value.id;closing=true;state.value='closed';if(timer)clearTimeout(timer)
  try {const latest=await read<Attempt>('/me/attempts/'+props.id);if(!active(owner))return;attempt.value=latest
    if(!await resultIfClosed(latest)){state.value=unconfirmed?'pending':'saved';if(unconfirmed)schedule()}
  } catch {if(active(owner))message.value='正在确认服务端结果。联网后将重新核对；未同步内容不会补写。'} finally {closing=false}
}
async function refreshClock() {
  if(stopped||document.visibilityState!=='visible'||!account.value||closing)return
  const owner=account.value.id
  try {const latest=await read<Attempt>('/me/attempts/'+props.id);if(!active(owner))return;attempt.value=latest;await resultIfClosed(latest)} catch { /* Server remains authoritative while offline. */ }
}
function navigate(id:string) { selected.value=id;const element=document.getElementById('question-'+id);element?.scrollIntoView({block:'start'});element?.querySelector('h2')?.focus({preventScroll:true}) }
async function submit() {
  if(submitting.value||state.value==='conflict'||!account.value)return
  submitting.value=true;confirming.value=false;message.value='';if(timer)clearTimeout(timer)
  try {
    while(busy&&!stopped)await new Promise(resolve=>setTimeout(resolve,25))
    if(stopped)return
    if(unconfirmed){await save();if(state.value==='failed'||state.value==='conflict'||state.value==='closed')return}
    if(!submission)submission={key:crypto.randomUUID(),body:{expectedVersion:baseVersion,answerItems:Object.entries(answers.value).map(([questionId,answer])=>({questionId,answer}))}}
    const result=await operation<Attempt>('/me/attempts/'+props.id+'/submit',submission.key,submission.body)
    if(stopped)return
    attempt.value=result;await resultIfClosed(result)
  } catch(cause) {
    if(stopped)return;message.value=(cause as Error).message
    try {const latest=await read<Attempt>('/me/attempts/'+props.id);if(stopped)return;attempt.value=latest
      if(!await resultIfClosed(latest)){if(cause instanceof ApiError&&cause.status>=400)submission=undefined;if(latest.version!==baseVersion){state.value='conflict';message.value='另一端已更新，请核对双方答案后再交卷。'}else{state.value='failed';message.value=submission?'交卷结果尚未确认，请使用原操作重试。':(cause as Error).message}}
    }catch{state.value='failed'}
  } finally {submitting.value=false}
}
onMounted(() => {
  window.addEventListener('online', online); window.addEventListener('storage', accountChanged)
  window.addEventListener('focus',refreshClock);document.addEventListener('visibilitychange',refreshClock)
  clockTimer=setInterval(()=>{remaining.value=Math.max(0,serverRemaining-(performance.now()-sampledAt));if(remaining.value===0&&attempt.value?.status==='IN_PROGRESS')void closeAndRead()},250)
  syncTimer=setInterval(()=>void refreshClock(),30000)
  void load()
})
onUnmounted(() => {
  stopped = true; if (timer) clearTimeout(timer); controller?.abort()
  if(clockTimer)clearInterval(clockTimer);if(syncTimer)clearInterval(syncTimer)
  window.removeEventListener('online', online); window.removeEventListener('storage', accountChanged)
  window.removeEventListener('focus',refreshClock);document.removeEventListener('visibilitychange',refreshClock)
})
</script>

<template>
  <main>
    <h1>答卷</h1>
    <RouterLink to="/home">返回学生中心</RouterLink>
    <p v-if="attempt">截止：{{ attempt.deadlineAt }}</p>
    <p>剩余时间：<strong data-testid="remaining-time">{{ remainingText }}</strong></p>
    <nav v-if="attempt" class="question-nav" aria-label="题号导航"><button v-for="(question,index) in attempt.questions" :key="question.questionId" type="button" :aria-current="selected===question.questionId" @click="navigate(question.questionId)">{{ index+1 }} · {{ answered(answers[question.questionId])?'已答':'未答' }}{{ marked.includes(question.questionId)?' · 已标记':'' }}</button></nav>
    <p role="status">{{ { loading: '正在加载', pending: '待同步', saving: '保存中', saved: '已保存',
      failed: '保存失败，请重试', conflict: '版本冲突', closed: '已截止' }[state] }}</p>
    <p v-if="message" role="alert">{{ message }}</p>
    <button v-if="!submission&&(state === 'failed' || state === 'pending')" type="button" @click="save">重试同步</button>
    <section v-for="(question, index) in attempt?.questions || []" :id="'question-'+question.questionId" :key="question.questionId">
      <h2 tabindex="-1">第 {{ index + 1 }} 题</h2>
      <button type="button" :aria-pressed="marked.includes(question.questionId)" @click="marked=marked.includes(question.questionId)?marked.filter(id=>id!==question.questionId):[...marked,question.questionId]">标记复查第 {{ index+1 }} 题</button>
      <RichContent :html="question.stemHtml" :image-prefix="`/api/v1/me/attempts/${id}/questions/${question.questionId}/attachments/`" />
      <p v-if="state === 'conflict'">服务器已保存：{{ serverAnswer(question) }}</p>
      <template v-if="question.type === 'SINGLE' || question.type === 'TRUE_FALSE'">
        <label v-for="option in question.type === 'TRUE_FALSE' ? [{ id: 'true', text: '正确' }, { id: 'false', text: '错误' }] : question.options" :key="option.id" class="answer-option">
          <input type="radio" :name="question.questionId" :value="option.id" :disabled="!editable"
            :checked="answers[question.questionId]?.optionId === option.id" @change="edit(question.questionId, { optionId: option.id })">{{ option.text }}
        </label>
      </template>
      <template v-else-if="question.type === 'MULTIPLE'">
        <label v-for="option in question.options" :key="option.id" class="answer-option">
          <input type="checkbox" :disabled="!editable"
            :checked="answers[question.questionId]?.optionIds?.includes(option.id)"
            @change="edit(question.questionId, { optionIds: answers[question.questionId]?.optionIds?.includes(option.id)
              ? answers[question.questionId].optionIds!.filter(id => id !== option.id)
              : [...(answers[question.questionId]?.optionIds || []), option.id] })">{{ option.text }}
        </label>
      </template>
      <textarea v-else-if="question.type === 'SHORT'" :value="answers[question.questionId]?.text || ''"
        :aria-label="'第 '+(index+1)+' 题答案'" :disabled="!editable" maxlength="10000"
        @input="edit(question.questionId, { text: ($event.target as HTMLTextAreaElement).value })"></textarea>
      <textarea v-else-if="question.type === 'GAP'" :value="answers[question.questionId]?.texts?.join('\n') || ''"
        :aria-label="'第 '+(index+1)+' 题答案（每行一空）'" :disabled="!editable"
        @input="edit(question.questionId, { texts: ($event.target as HTMLTextAreaElement).value.split('\n') })"></textarea>
      <button type="button" :disabled="!editable"
        @click="answers = Object.fromEntries(Object.entries(answers).filter(([id]) => id !== question.questionId)); revision++; state = 'pending'; persist(); schedule()">清空本题</button>
    </section>
    <p v-if="state === 'conflict'">本地草稿已保留。可核对上方服务器答案后加载服务器版本。</p>
    <button v-if="state === 'conflict'" type="button" @click="useServerAnswers">加载服务器答案并丢弃本地草稿</button>
    <button v-if="attempt&&state!=='closed'" type="button" :disabled="submitting||state==='conflict'" @click="submission?submit():confirming=true">{{ submitting?'正在交卷…':submission?'重试原交卷请求':'准备交卷' }}</button>
    <div v-if="confirming" role="dialog" aria-modal="true" aria-labelledby="submit-title"><h2 id="submit-title">确认交卷</h2><p>还有 {{ unanswered }} 题未作答。交卷后不能修改答案。</p><button type="button" :disabled="submitting" @click="submit">确认交卷并冻结答案</button><button type="button" @click="confirming=false">继续答题</button></div>
  </main>
</template>
