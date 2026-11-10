<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { read, write, type Grade } from '../../shared/api'
import { type Page, type Subject } from './catalog'
import CatalogNav from './CatalogNav.vue'

const grades = ref<Grade[]>([])
const items = ref<Subject[]>([])
const gradeId = ref('')
const page = ref(1)
const total = ref(0)
const error = ref('')
const notice = ref('')
const loading = ref(false)
const busy = ref(false)
const editing = ref<Subject>()
const name = ref('')
const subjectGrade = ref('')
const confirm = ref(false)

async function load(next = page.value) {
  loading.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({ page: String(next) })
    if (gradeId.value) query.set('gradeId', gradeId.value)
    const data = await read<Page<Subject>>('/admin/subjects?' + query)
    items.value = data.items
    total.value = data.total
    page.value = data.page
    if (!grades.value.length) grades.value = await read<Grade[]>('/catalog/grades')
  } catch (cause) {
    items.value = []
    error.value = (cause as Error).message
  } finally {
    loading.value = false
  }
}

function select(subject?: Subject) {
  editing.value = subject
  name.value = subject?.name ?? ''
  subjectGrade.value = subject?.gradeId ?? gradeId.value
  confirm.value = false
  error.value = ''
  notice.value = ''
}

async function save() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const existing = editing.value
    if (existing) {
      await write('PATCH', '/admin/subjects/' + existing.id, { name: name.value, expectedVersion: existing.version })
    } else {
      await write('POST', '/admin/subjects', { name: name.value, gradeId: subjectGrade.value })
    }
    select()
    notice.value = '学科已保存。'
    await load()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}

async function remove() {
  const subject = editing.value
  if (!subject) return
  busy.value = true
  error.value = ''
  try {
    await write('DELETE', '/admin/subjects/' + subject.id, { expectedVersion: subject.version })
    select()
    notice.value = '学科已删除。'
    await load(items.value.length === 1 && page.value > 1 ? page.value - 1 : page.value)
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    confirm.value = false
    busy.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <main>
    <CatalogNav />
    <h1>学科管理</h1>
    <form @submit.prevent="load(1)">
      <label>筛选年级
        <select v-model="gradeId">
          <option value="">全部</option>
          <option v-for="grade in grades" :key="grade.id" :value="grade.id">{{ grade.name }}</option>
        </select>
      </label>
      <button :disabled="loading || busy">筛选</button>
    </form>
    <p v-if="loading" role="status">正在加载学科…</p>
    <p v-if="error" role="alert">{{ error }} <button type="button" @click="load()">刷新列表</button></p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="!loading && !error && !items.length">暂无学科。</p>
    <ul v-if="!loading && items.length">
      <li v-for="subject in items" :key="subject.id">
        {{ subject.name }}
        <button type="button" :disabled="busy" :aria-label="'编辑 ' + subject.name" @click="select(subject)">编辑</button>
      </li>
    </ul>
    <nav aria-label="学科分页">
      <button type="button" :disabled="loading || busy || page <= 1" @click="load(page - 1)">上一页</button>
      <button type="button" :disabled="loading || busy || page * 20 >= total" @click="load(page + 1)">下一页</button>
    </nav>
    <h2>{{ editing ? '编辑学科' : '新建学科' }}</h2>
    <form @submit.prevent="save">
      <label>学科名称<input v-model="name" required maxlength="80" :disabled="busy"></label>
      <label>所属年级
        <select v-model="subjectGrade" required :disabled="Boolean(editing) || busy">
          <option disabled value="">选择年级</option>
          <option v-for="grade in grades" :key="grade.id" :value="grade.id">{{ grade.name }}</option>
        </select>
      </label>
      <button :disabled="busy || !subjectGrade">保存学科</button>
    </form>
    <nav v-if="editing" aria-label="编辑学科操作">
      <button v-if="!confirm" type="button" :disabled="busy" @click="confirm = true">删除学科</button>
      <template v-else>
        <p>确认删除该学科？</p>
        <button type="button" :disabled="busy" @click="remove">确认删除</button>
      </template>
      <button type="button" :disabled="busy" @click="select()">返回新建</button>
    </nav>
  </main>
</template>
