<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { read, write } from '../../shared/api'
import {
  allSubjects, paperTypes, types, type Page, type PaperType, type PaperView,
  type Question, type QuestionSummary, type Subject,
} from './catalog'
import CatalogNav from './CatalogNav.vue'
import QuestionContent from './QuestionContent.vue'

type Group = { key: number; name: string; questionIds: string[]; add: string }
const props = defineProps<{ id?: string }>()
const router = useRouter()
const subjects = ref<Subject[]>([])
const subjectId = ref('')
const type = ref<PaperType>('FIXED')
const name = ref('')
const durationMinutes = ref(30)
const opens = ref('')
const closes = ref('')
const groups = ref<Group[]>([])
const library = ref<QuestionSummary[]>([])
const bank = ref<Record<string, QuestionSummary>>({})
const libraryPage = ref(1)
const libraryTotal = ref(0)
const view = ref<PaperView>()
const preview = ref<Question>()
const ready = ref(false)
const busy = ref(false)
const libraryBusy = ref(false)
const error = ref('')
const notice = ref('')
const confirmation = ref(false)
let groupKey = 0
const chosen = computed(() => new Set(groups.value.flatMap((group) => group.questionIds)))
const format = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Hong_Kong', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})

function localTime(value: string | null) {
  return value ? format.format(new Date(value)).replace(' ', 'T') : ''
}

function isoTime(value: string) {
  if (!value) return null
  return new Date(value + (value.length === 16 ? ':00' : '') + '+08:00').toISOString()
}

function populate(next: PaperView) {
  view.value = next
  subjectId.value = next.paper.subjectId
  type.value = next.paper.type
  name.value = next.content.name
  durationMinutes.value = next.content.durationMinutes
  opens.value = localTime(next.content.opensAt)
  closes.value = localTime(next.content.closesAt)
  const grouped = new Map<number, Group>()
  for (const item of next.items) {
    if (!grouped.has(item.groupPosition)) {
      grouped.set(item.groupPosition, { key: ++groupKey, name: item.groupName, questionIds: [], add: '' })
    }
    grouped.get(item.groupPosition)!.questionIds.push(item.questionId)
  }
  groups.value = [...grouped.values()]
}

async function load() {
  busy.value = true
  error.value = ''
  try {
    subjects.value = await allSubjects()
    if (props.id) {
      populate(await read<PaperView>('/admin/papers/' + props.id))
      await loadLibrary(1)
    }
    ready.value = true
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}

async function loadLibrary(page: number) {
  if (!subjectId.value) return
  libraryBusy.value = true
  try {
    const data = await read<Page<QuestionSummary>>('/admin/questions?' + new URLSearchParams({
      subjectId: subjectId.value, page: String(page),
    }))
    library.value = data.items
    libraryPage.value = data.page
    libraryTotal.value = data.total
    for (const item of data.items) bank.value[item.id] = item
  } catch (cause) {
    library.value = []
    error.value = (cause as Error).message
  } finally {
    libraryBusy.value = false
  }
}

function changeSubject() {
  groups.value = []
  bank.value = {}
  void loadLibrary(1)
}

function label(item: QuestionSummary) {
  return types[item.type] + ' · ' + (item.stemPreview || item.id)
}

function addQuestion(group: Group) {
  if (!group.add || chosen.value.has(group.add) || chosen.value.size >= 200) return
  group.questionIds.push(group.add)
  group.add = ''
}

function move<T>(items: T[], index: number, by: number) {
  const next = index + by
  if (next < 0 || next >= items.length) return
  const [item] = items.splice(index, 1)
  items.splice(next, 0, item)
}

async function save() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const draft = {
      name: name.value, durationMinutes: durationMinutes.value,
      opensAt: type.value === 'TIMED' ? isoTime(opens.value) : null,
      closesAt: type.value === 'TIMED' ? isoTime(closes.value) : null,
      groups: groups.value.map((group) => ({ name: group.name, questionIds: group.questionIds })),
    }
    if (view.value) {
      populate(await write<PaperView>('PATCH', '/admin/papers/' + view.value.paper.id, {
        expectedVersion: view.value.paper.version, draft,
      }))
      notice.value = '草稿已保存。'
    } else {
      const created = await write<PaperView>('POST', '/admin/papers', { subjectId: subjectId.value, type: type.value, draft })
      await router.replace('/papers/' + created.paper.id + '/edit')
    }
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}

async function action(kind: 'publish' | 'delete') {
  const paper = view.value?.paper
  if (!paper) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const body = { expectedVersion: paper.version }
    const path = '/admin/papers/' + paper.id
    if (kind === 'publish') {
      populate(await write<PaperView>('POST', path + '/publish', body))
      notice.value = '已发布保存的草稿。'
    } else {
      await write('DELETE', path, body)
      await router.replace('/papers')
    }
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
    confirmation.value = false
  }
}

async function showQuestion(id: string, version: number) {
  error.value = ''
  preview.value = undefined
  try {
    preview.value = await read<Question>('/admin/questions/' + id + '?version=' + version)
  } catch (cause) {
    error.value = (cause as Error).message
  }
}

onMounted(load)
</script>

<template>
  <main>
    <CatalogNav />
    <h1>{{ id ? '编辑试卷' : '新建试卷' }}</h1>
    <p v-if="busy" role="status">正在处理…</p>
    <p v-if="error" role="alert">{{ error }} <button type="button" :disabled="busy" @click="load">重新加载</button></p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="ready && !subjects.length">暂无学科，请先创建学科。</p>
    <form v-if="ready && !view?.paper.archived" @submit.prevent="save">
      <fieldset :disabled="busy">
        <legend>试卷设置</legend>
        <label>试卷名称<input v-model="name" maxlength="100" required></label>
        <label>学科
          <select v-model="subjectId" :disabled="Boolean(id) || libraryBusy" required @change="changeSubject">
            <option disabled value="">选择学科</option>
            <option v-for="subject in subjects" :key="subject.id" :value="subject.id">{{ subject.name }}</option>
          </select>
        </label>
        <label>卷型
          <select v-model="type" :disabled="Boolean(id)">
            <option v-for="(label, key) in paperTypes" :key="key" :value="key">{{ label }}</option>
          </select>
        </label>
        <label>时长（分钟）<input v-model.number="durationMinutes" type="number" min="1" max="180" required></label>
        <template v-if="type === 'TIMED'">
          <label>开放时间（香港时间）<input v-model="opens" type="datetime-local" step="1"></label>
          <label>关闭时间（香港时间）<input v-model="closes" type="datetime-local" step="1"></label>
        </template>
      </fieldset>
      <button type="button" :disabled="!subjectId || busy || groups.length >= 200"
        @click="groups.push({ key: ++groupKey, name: '', questionIds: [], add: '' })">添加分组</button>
      <nav v-if="subjectId" aria-label="可选题库分页">
        <button type="button" :disabled="libraryBusy || libraryPage <= 1" @click="loadLibrary(libraryPage - 1)">题库上一页</button>
        <span>题库第 {{ libraryPage }} 页，共 {{ libraryTotal }} 题</span>
        <button type="button" :disabled="libraryBusy || libraryPage * 20 >= libraryTotal" @click="loadLibrary(libraryPage + 1)">题库下一页</button>
      </nav>
      <fieldset v-for="(group, index) in groups" :key="group.key" :disabled="busy">
        <legend>分组 {{ index + 1 }}</legend>
        <label>分组名称<input v-model="group.name" maxlength="80" required></label>
        <nav aria-label="分组顺序">
          <button type="button" :disabled="index === 0" @click="move(groups, index, -1)">上移分组</button>
          <button type="button" :disabled="index === groups.length - 1" @click="move(groups, index, 1)">下移分组</button>
          <button type="button" @click="groups.splice(index, 1)">删除分组</button>
        </nav>
        <label>从题库选择题目
          <select v-model="group.add" :disabled="libraryBusy">
            <option value="">选择题目</option>
            <option v-for="item in library" :key="item.id" :disabled="chosen.has(item.id)" :value="item.id">{{ label(item) }}</option>
          </select>
        </label>
        <button type="button" :disabled="!group.add || chosen.size >= 200" @click="addQuestion(group)">加入分组</button>
        <ol>
          <li v-for="(qid, position) in group.questionIds" :key="qid">
            {{ bank[qid] ? label(bank[qid]) : qid }}
            <button type="button" :disabled="position === 0" @click="move(group.questionIds, position, -1)">上移题目</button>
            <button type="button" :disabled="position === group.questionIds.length - 1" @click="move(group.questionIds, position, 1)">下移题目</button>
            <button type="button" @click="group.questionIds.splice(position, 1)">移除题目</button>
          </li>
        </ol>
      </fieldset>
      <button :disabled="busy || !subjectId">保存试卷草稿</button>
    </form>
    <section v-if="view">
      <h2>已保存版本预览</h2>
      <p>{{ view.content.name }} · 第 {{ view.content.revision }} 版 · {{ view.items.length }} 题 · {{ view.totalScore }} 分</p>
      <p v-if="view.paper.archived">已归档</p>
      <ol>
        <li v-for="item in view.items" :key="item.questionId">
          {{ item.groupName }} · {{ item.score }} 分
          <button type="button" @click="showQuestion(item.questionId, item.questionVersion)">预览第 {{ item.position }} 题</button>
        </li>
      </ol>
      <div v-if="preview">
        <h3>题干预览</h3>
        <QuestionContent :id="preview.id" :version="preview.version" :html="preview.stemHtml" />
        <ol v-if="preview.options.length"><li v-for="option in preview.options" :key="option.id">{{ option.text }}</li></ol>
      </div>
      <nav v-if="!view.paper.archived" aria-label="试卷操作">
        <button v-if="view.paper.draftId" type="button" :disabled="busy" @click="action('publish')">发布已保存草稿</button>
        <button v-if="!confirmation" type="button" :disabled="busy" @click="confirmation = true">
          {{ view.paper.publishedId ? '归档试卷' : '删除草稿' }}
        </button>
        <template v-else>
          <p>{{ view.paper.publishedId ? '归档后停止新的作答。' : '确认删除尚未发布的草稿？' }}</p>
          <button type="button" :disabled="busy" @click="action('delete')">确认操作</button>
          <button type="button" :disabled="busy" @click="confirmation = false">取消</button>
        </template>
      </nav>
    </section>
  </main>
</template>
