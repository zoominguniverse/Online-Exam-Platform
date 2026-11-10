<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { read, write } from '../../shared/api'
import { allSubjects, types, type Question, type Subject, type Type } from './catalog'
import CatalogNav from './CatalogNav.vue'
import QuestionContent from './QuestionContent.vue'

const props = defineProps<{ id?: string }>()
const router = useRouter()
const subjects = ref<Subject[]>([])
const question = ref<Question>()
const subjectId = ref('')
const type = ref<Type>('SINGLE')
const difficulty = ref(3)
const score = ref('1.00')
const stemHtml = ref('')
const explanationHtml = ref('')
const options = ref([{ id: 'A', text: '' }, { id: 'B', text: '' }])
const selected = ref<string[]>([])
const single = ref('')
const truth = ref('true')
const gaps = ref('')
const reference = ref('')
const loading = ref(false)
const ready = ref(false)
const busy = ref(false)
const confirming = ref(false)
const error = ref('')
const notice = ref('')
const choice = computed(() => type.value === 'SINGLE' || type.value === 'MULTIPLE')

function apply(value: Question) {
  question.value = value
  subjectId.value = value.subjectId
  type.value = value.type
  difficulty.value = value.difficulty
  score.value = value.score
  stemHtml.value = value.stemHtml
  explanationHtml.value = value.explanationHtml
  if (value.options.length) options.value = value.options
  selected.value = value.type === 'MULTIPLE' ? value.answer.optionIds ?? [] : []
  single.value = value.type === 'SINGLE' ? value.answer.optionIds?.[0] ?? '' : ''
  truth.value = value.type === 'TRUE_FALSE' ? value.answer.optionIds?.[0] ?? 'true' : 'true'
  gaps.value = value.answer.gaps?.join('\n') ?? ''
  reference.value = value.answer.text ?? ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    subjects.value = await allSubjects()
    if (props.id) apply(await read<Question>('/admin/questions/' + props.id))
    ready.value = true
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    loading.value = false
  }
}

function buildAnswer() {
  switch (type.value) {
    case 'SINGLE': return { optionIds: [single.value] }
    case 'MULTIPLE': return { optionIds: selected.value }
    case 'TRUE_FALSE': return { optionIds: [truth.value] }
    case 'GAP': return { gaps: gaps.value.split('\n').map((gap) => gap.trim()) }
    case 'SHORT': return { text: reference.value }
  }
}

function addOption() {
  if (options.value.length < 10) options.value.push({ id: crypto.randomUUID(), text: '' })
}

function removeOption(id: string) {
  options.value = options.value.filter((option) => option.id !== id)
  if (single.value === id) single.value = ''
  selected.value = selected.value.filter((value) => value !== id)
}

async function save() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const draft = {
      subjectId: subjectId.value, type: type.value, difficulty: difficulty.value, score: score.value,
      stemHtml: stemHtml.value, explanationHtml: explanationHtml.value,
      options: choice.value ? options.value : [], answer: buildAnswer(),
    }
    if (question.value) {
      apply(await write<Question>('PATCH', '/admin/questions/' + question.value.id,
        { question: draft, expectedVersion: question.value.version }))
      notice.value = '新版本已保存。'
    } else {
      const created = await write<Question>('POST', '/admin/questions', draft)
      await router.replace('/questions/' + created.id + '/edit')
    }
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}

async function addImage(event: Event, destination: 'stem' | 'explanation') {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    const image = await write<{ id: string }>('POST', '/attachments', form)
    const content = destination === 'stem' ? stemHtml : explanationHtml
    content.value += '\n<img data-attachment="' + image.id + '" alt="题目图片">'
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}

async function archive() {
  if (!question.value) return
  busy.value = true
  error.value = ''
  try {
    await write('DELETE', '/admin/questions/' + question.value.id, { expectedVersion: question.value.version })
    await router.replace('/questions')
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
    confirming.value = false
  }
}

onMounted(load)
</script>

<template>
  <main>
    <CatalogNav />
    <h1>{{ id ? '编辑题目' : '新建题目' }}</h1>
    <p v-if="loading" role="status">正在加载题目表单…</p>
    <p v-if="error" role="alert">{{ error }} <button type="button" :disabled="busy || loading" @click="load">重新加载</button></p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="question">当前版本 {{ question.version }}{{ question.archived ? '（已归档）' : '' }}</p>
    <p v-if="ready && !subjects.length">暂无学科，请先创建学科。</p>
    <form v-if="ready && !question?.archived" @submit.prevent="save">
      <label>学科
        <select v-model="subjectId" required>
          <option disabled value="">选择学科</option>
          <option v-for="subject in subjects" :key="subject.id" :value="subject.id">{{ subject.name }}</option>
        </select>
      </label>
      <label>题型
        <select v-model="type">
          <option v-for="(name, value) in types" :key="value" :value="value">{{ name }}</option>
        </select>
      </label>
      <label>难度（1–5）<input v-model.number="difficulty" type="number" min="1" max="5" required></label>
      <label>分值（两位小数）<input v-model="score" inputmode="decimal" required></label>
      <label>题干<textarea v-model="stemHtml" rows="5" maxlength="10000" required></textarea></label>
      <label>上传题干图片<input type="file" accept="image/png,image/jpeg,image/webp" :disabled="busy" @change="addImage($event, 'stem')"></label>
      <fieldset v-if="choice">
        <legend>选项与正确答案</legend>
        <div v-for="(option, index) in options" :key="option.id" class="option">
          <label>选项 {{ index + 1 }}<input v-model="option.text" maxlength="2000" required></label>
          <label v-if="type === 'SINGLE'">
            <input v-model="single" type="radio" name="single" :value="option.id" required>正确选项 {{ index + 1 }}
          </label>
          <label v-else><input v-model="selected" type="checkbox" :value="option.id">正确选项 {{ index + 1 }}</label>
          <button v-if="options.length > 2" type="button" @click="removeOption(option.id)">移除选项 {{ index + 1 }}</button>
        </div>
        <button type="button" :disabled="options.length >= 10" @click="addOption">添加选项</button>
      </fieldset>
      <label v-if="type === 'TRUE_FALSE'">正确答案
        <select v-model="truth"><option value="true">正确</option><option value="false">错误</option></select>
      </label>
      <template v-if="type === 'GAP'">
        <p>在题干中使用 <code>&lt;span data-gap="1"&gt;____&lt;/span&gt;</code> 插入空位，并从 1 顺序编号。</p>
        <label>每行一个参考答案<textarea v-model="gaps" rows="4" required></textarea></label>
      </template>
      <label v-if="type === 'SHORT'">参考答案<textarea v-model="reference" rows="4" maxlength="10000" required></textarea></label>
      <label>解析<textarea v-model="explanationHtml" rows="4" maxlength="10000"></textarea></label>
      <label>上传解析图片<input type="file" accept="image/png,image/jpeg,image/webp" :disabled="busy" @change="addImage($event, 'explanation')"></label>
      <button :disabled="busy || !subjects.length">保存题目</button>
    </form>
    <section v-if="question">
      <h2>已保存题干</h2>
      <QuestionContent :id="question.id" :version="question.version" :html="question.stemHtml" />
      <template v-if="question.explanationHtml">
        <h2>已保存解析</h2>
        <QuestionContent :id="question.id" :version="question.version" :html="question.explanationHtml" />
      </template>
      <button v-if="!question.archived && !confirming" type="button" :disabled="busy" @click="confirming = true">归档题目</button>
      <nav v-if="confirming" aria-label="归档题目确认">
        <p>归档后无法将此题添加到新试卷。</p>
        <button type="button" :disabled="busy" @click="archive">确认归档</button>
        <button type="button" :disabled="busy" @click="confirming = false">取消</button>
      </nav>
    </section>
  </main>
</template>

<style scoped>
textarea { font: inherit; width: 100%; box-sizing: border-box; padding: .5rem .75rem; }
.option { margin: 1rem 0; }
.option label + label { display: flex; align-items: center; margin: .25rem 0; }
</style>
