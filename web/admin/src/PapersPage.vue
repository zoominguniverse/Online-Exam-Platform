<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { read } from '../../shared/api'
import { type Page, type PaperSummary, paperTypes } from './catalog'
import CatalogNav from './CatalogNav.vue'

const items = ref<PaperSummary[]>([])
const page = ref(1)
const total = ref(0)
const type = ref('')
const archived = ref(false)
const loading = ref(false)
const error = ref('')

async function load(next = page.value) {
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({ page: String(next), includeArchived: String(archived.value) })
    if (type.value) params.set('type', type.value)
    const data = await read<Page<PaperSummary>>('/admin/papers?' + params)
    items.value = data.items
    total.value = data.total
    page.value = data.page
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <main>
    <CatalogNav />
    <h1>试卷管理</h1>
    <RouterLink to="/papers/new">新建试卷</RouterLink>
    <form @submit.prevent="load(1)">
      <label>试卷类型
        <select v-model="type">
          <option value="">全部</option>
          <option v-for="(label, key) in paperTypes" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <label><input v-model="archived" type="checkbox">包含已归档试卷</label>
      <button :disabled="loading">筛选</button>
    </form>
    <p v-if="loading" role="status">正在加载试卷…</p>
    <p v-else-if="error" role="alert">{{ error }} <button type="button" @click="load()">重试</button></p>
    <p v-else-if="!items.length">暂无试卷。</p>
    <div v-else class="table-scroll">
      <table>
        <thead><tr><th scope="col">名称</th><th scope="col">类型</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td>{{ item.name }}</td><td>{{ paperTypes[item.type] }}</td>
            <td>{{ item.archived ? '已归档' : item.draftId ? (item.publishedId ? '已发布，有待发布草稿' : '草稿') : '已发布' }}</td>
            <td><RouterLink :to="'/papers/' + item.id + '/edit'">{{ item.archived ? '查看试卷' : '编辑试卷' }}</RouterLink></td>
          </tr>
        </tbody>
      </table>
    </div>
    <nav aria-label="试卷分页">
      <button type="button" :disabled="loading || page <= 1" @click="load(page - 1)">上一页</button>
      <button type="button" :disabled="loading || page * 20 >= total" @click="load(page + 1)">下一页</button>
    </nav>
  </main>
</template>
