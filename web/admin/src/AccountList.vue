<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, read, write, type Account, type Grade, type Role } from '../../shared/api'

const props = defineProps<{ role: Role }>()
const router = useRouter()
const items = ref<Account[]>([])
const grades = ref<Grade[]>([])
const grade = ref('')
const page = ref(1)
const total = ref(0)
const error = ref('')
const loading = ref(false)
const loggingOut = ref(false)
const kind = computed(() => props.role === 'ADMIN' ? 'admins' : 'students')

async function handle(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 401) await router.replace({ name: 'login' })
  else if (cause instanceof ApiError && cause.status === 403) await router.replace({ name: 'denied' })
  else error.value = (cause as Error).message
}

async function load() {
  loading.value = true
  error.value = ''
  items.value = []
  try {
    const params = new URLSearchParams({ role: props.role, page: String(page.value) })
    if (grade.value) params.set('gradeId', grade.value)
    const data = await read<{ items: Account[]; total: number }>('/admin/users?' + params.toString())
    items.value = data.items
    total.value = data.total
    if (props.role === 'STUDENT' && !grades.value.length) grades.value = await read<Grade[]>('/catalog/grades')
  } catch (cause) {
    await handle(cause)
  } finally {
    loading.value = false
  }
}

async function turn(nextPage: number) {
  page.value = nextPage
  await load()
}

async function logout() {
  loggingOut.value = true
  try {
    await write('POST', '/auth/logout')
    await router.replace({ name: 'login' })
  } catch (cause) {
    await handle(cause)
  } finally {
    loggingOut.value = false
  }
}

onMounted(load)
</script>

<template>
  <main>
    <nav aria-label="账号管理">
      <RouterLink to="/users/students">学生列表</RouterLink>
      <RouterLink to="/users/admins">管理员列表</RouterLink>
      <RouterLink to="/subjects">学科管理</RouterLink>
      <RouterLink to="/questions">题库管理</RouterLink>
      <RouterLink to="/papers">试卷管理</RouterLink>
      <RouterLink to="/tasks">年级任务</RouterLink>
      <button type="button" :disabled="loggingOut" @click="logout">退出登录</button>
    </nav>
    <h1>{{ role === 'ADMIN' ? '管理员列表' : '学生列表' }}</h1>
    <RouterLink :to="'/users/new/' + kind">新增账号</RouterLink>
    <form v-if="role === 'STUDENT'" @submit.prevent="turn(1)">
      <label>年级
        <select v-model="grade">
          <option value="">全部</option>
          <option v-for="item in grades" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
      </label>
      <button :disabled="loading">筛选</button>
    </form>
    <p v-if="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <p v-if="loading" role="status">正在加载账号…</p>
    <p v-else-if="!error && !items.length">没有匹配的账号。</p>
    <div v-else-if="items.length" class="table-scroll">
      <table>
        <thead><tr><th scope="col">用户名</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td>{{ item.username }}</td>
            <td>{{ item.status === 'ENABLED' ? '启用' : '禁用' }}</td>
            <td><RouterLink :to="'/users/' + item.id + '/edit'" :aria-label="'编辑 ' + item.username">编辑</RouterLink></td>
          </tr>
        </tbody>
      </table>
    </div>
    <nav v-if="!error" aria-label="分页">
      <button type="button" :disabled="loading || page <= 1" @click="turn(page - 1)">上一页</button>
      <p>第 {{ page }} 页，共 {{ total }} 个账号</p>
      <button type="button" :disabled="loading || page * 20 >= total" @click="turn(page + 1)">下一页</button>
    </nav>
  </main>
</template>
