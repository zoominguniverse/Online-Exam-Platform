<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { read, write, type Grade } from '../../shared/api'
import { type Page, type PaperSummary } from './catalog'
import CatalogNav from './CatalogNav.vue'

type State = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
type Task = { id: string; name: string; gradeId: string; state: State; version: number; paperIds: string[] }
type View = { task: Task; papers: { paperId: string; name: string; position: number }[]; recipientCount: number }
const states = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' }
const grades = ref<Grade[]>([])
const items = ref<Task[]>([])
const papers = ref<PaperSummary[]>([])
const paperName = ref<Record<string, string>>({})
const detail = ref<View>()
const name = ref('')
const gradeId = ref('')
const selected = ref<string[]>([])
const add = ref('')
const listPage = ref(1)
const total = ref(0)
const paperPage = ref(1)
const paperTotal = ref(0)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const confirming = ref(false)
const editable = computed(() => !detail.value || detail.value.task.state === 'DRAFT')

async function load(next = listPage.value) {
  if (!grades.value.length) grades.value = await read<Grade[]>('/catalog/grades')
  const result = await read<Page<Task>>('/admin/tasks?page=' + next)
  items.value = result.items
  total.value = result.total
  listPage.value = result.page
}

async function available(next = 1) {
  papers.value = []
  add.value = ''
  if (!gradeId.value) return
  const query = new URLSearchParams({ type: 'TASK', gradeId: gradeId.value, page: String(next) })
  const result = await read<Page<PaperSummary>>('/admin/papers?' + query)
  papers.value = result.items.filter((paper) => paper.publishedId)
  paperPage.value = result.page
  paperTotal.value = result.total
  for (const paper of papers.value) paperName.value[paper.id] = paper.name
}

async function perform(run: () => Promise<void>) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await run()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}

function reset() {
  detail.value = undefined
  name.value = ''
  gradeId.value = ''
  selected.value = []
  papers.value = []
  confirming.value = false
}

async function inspect(id: string) {
  const value = await read<View>('/admin/tasks/' + id)
  detail.value = value
  name.value = value.task.name
  gradeId.value = value.task.gradeId
  selected.value = value.task.paperIds
  confirming.value = false
  if (editable.value) await available()
}

function pick() {
  if (add.value && !selected.value.includes(add.value) && selected.value.length < 200) selected.value.push(add.value)
  add.value = ''
}

async function save() {
  const draft = { name: name.value, gradeId: gradeId.value, paperIds: selected.value }
  const existing = detail.value?.task
  const created = existing
    ? await write<Task>('PATCH', '/admin/tasks/' + existing.id, { expectedVersion: existing.version, draft })
    : await write<Task>('POST', '/admin/tasks', draft)
  await inspect(created.id)
  await load()
  notice.value = '任务草稿已保存。'
}

async function change(kind: 'publish' | 'delete') {
  const task = detail.value?.task
  if (!task) return
  const path = '/admin/tasks/' + task.id
  const body = { expectedVersion: task.version }
  if (kind === 'publish') {
    detail.value = await write<View>('POST', path + '/publish', body)
    notice.value = '已发布保存的任务草稿。'
  } else {
    await write('DELETE', path, body)
    reset()
    notice.value = '任务已处理。'
  }
  await load()
}

onMounted(() => perform(() => load()))
</script>

<template>
  <main>
    <CatalogNav />
    <h1>年级任务管理</h1>
    <p v-if="busy" role="status">正在处理…</p>
    <p v-if="error" role="alert">{{ error }} <button type="button" :disabled="busy" @click="perform(() => load())">刷新列表</button></p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="!busy && !error && !items.length">暂无年级任务。</p>
    <ul>
      <li v-for="task in items" :key="task.id">
        {{ task.name }} · {{ states[task.state] }}
        <button type="button" :aria-label="'查看 ' + task.name" :disabled="busy" @click="perform(() => inspect(task.id))">查看</button>
      </li>
    </ul>
    <nav aria-label="任务分页">
      <button type="button" :disabled="busy || listPage <= 1" @click="perform(() => load(listPage - 1))">上一页</button>
      <button type="button" :disabled="busy || listPage * 20 >= total" @click="perform(() => load(listPage + 1))">下一页</button>
      <button type="button" :disabled="busy" @click="reset">新建任务</button>
    </nav>
    <h2>{{ detail?.task.name ?? '新建任务' }}</h2>
    <form v-if="editable" @submit.prevent="perform(save)">
      <label>任务名称<input v-model="name" maxlength="100" required :disabled="busy"></label>
      <label>目标年级
        <select v-model="gradeId" :disabled="busy" required @change="selected = []; perform(() => available())">
          <option disabled value="">选择年级</option>
          <option v-for="grade in grades" :key="grade.id" :value="grade.id">{{ grade.name }}</option>
        </select>
      </label>
      <label v-if="gradeId">添加已发布任务卷
        <select v-model="add" :disabled="busy">
          <option value="">选择试卷</option>
          <option v-for="paper in papers" :key="paper.id" :disabled="selected.includes(paper.id)" :value="paper.id">{{ paper.name }}</option>
        </select>
      </label>
      <button type="button" :disabled="busy || !add" @click="pick">添加试卷</button>
      <nav v-if="gradeId" aria-label="可选试卷分页">
        <button type="button" :disabled="busy || paperPage <= 1" @click="perform(() => available(paperPage - 1))">试卷上一页</button>
        <button type="button" :disabled="busy || paperPage * 20 >= paperTotal" @click="perform(() => available(paperPage + 1))">试卷下一页</button>
      </nav>
      <ol>
        <li v-for="(id, index) in selected" :key="id">
          {{ paperName[id] ?? id }}
          <button type="button" :disabled="busy" @click="selected.splice(index, 1)">移除</button>
        </li>
      </ol>
      <button :disabled="busy || !gradeId">保存任务草稿</button>
    </form>
    <section v-if="detail && !editable">
      <p>{{ states[detail.task.state] }} · {{ detail.recipientCount }} 名目标学生</p>
      <ol><li v-for="paper in detail.papers" :key="paper.paperId">{{ paper.name }}</li></ol>
    </section>
    <nav v-if="detail && detail.task.state !== 'ARCHIVED'" aria-label="任务操作">
      <button v-if="editable" type="button" :disabled="busy" @click="perform(() => change('publish'))">发布已保存任务</button>
      <button v-if="!confirming" type="button" :disabled="busy" @click="confirming = true">
        {{ editable ? '删除草稿' : '归档任务' }}
      </button>
      <template v-else>
        <button type="button" :disabled="busy" @click="perform(() => change('delete'))">确认操作</button>
        <button type="button" :disabled="busy" @click="confirming = false">取消</button>
      </template>
    </nav>
  </main>
</template>
