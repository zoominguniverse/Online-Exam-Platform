<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { read } from '../../shared/api'
import { allSubjects, types, type Page, type QuestionSummary, type Subject } from './catalog'
import CatalogNav from './CatalogNav.vue'

const items = ref<QuestionSummary[]>([])
const subjects = ref<Subject[]>([])
const subjectId = ref('')
const type = ref('')
const page = ref(1)
const total = ref(0)
const busy = ref(false)
const error = ref('')

async function load(next = page.value) {
  busy.value = true
  error.value = ''
  try {
    if (!subjects.value.length) subjects.value = await allSubjects()
    const query = new URLSearchParams({ page: String(next) })
    if (subjectId.value) query.set('subjectId', subjectId.value)
    if (type.value) query.set('type', type.value)
    const data = await read<Page<QuestionSummary>>('/admin/questions?' + query)
    items.value = data.items
    page.value = data.page
    total.value = data.total
  } catch (cause) {
    items.value = []
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <main>
    <CatalogNav />
    <h1>题库管理</h1>
    <RouterLink to="/questions/new">新建题目</RouterLink>
    <form @submit.prevent="load(1)">
      <label>筛选学科
        <select v-model="subjectId">
          <option value="">全部</option>
          <option v-for="subject in subjects" :key="subject.id" :value="subject.id">{{ subject.name }}</option>
        </select>
      </label>
      <label>筛选题型
        <select v-model="type">
          <option value="">全部</option>
          <option v-for="(label, value) in types" :key="value" :value="value">{{ label }}</option>
        </select>
      </label>
      <button :disabled="busy">筛选</button>
    </form>
    <p v-if="busy" role="status">正在加载题库…</p>
    <p v-else-if="error" role="alert">{{ error }} <button type="button" @click="load()">重试</button></p>
    <p v-else-if="!items.length">暂无题目。</p>
    <div v-else class="table-scroll">
      <table>
        <thead><tr><th scope="col">学科</th><th scope="col">题型</th><th scope="col">分值</th><th scope="col">操作</th></tr></thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td>{{ subjects.find((subject) => subject.id === item.subjectId)?.name ?? item.subjectId }}</td>
            <td>{{ types[item.type] }}</td><td>{{ item.score }}</td>
            <td><RouterLink :to="'/questions/' + item.id + '/edit'">编辑题目</RouterLink></td>
          </tr>
        </tbody>
      </table>
    </div>
    <nav aria-label="题库分页">
      <button type="button" :disabled="busy || page <= 1" @click="load(page - 1)">上一页</button>
      <button type="button" :disabled="busy || page * 20 >= total" @click="load(page + 1)">下一页</button>
    </nav>
  </main>
</template>
