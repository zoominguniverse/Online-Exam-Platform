<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, read, write, type Account, type Grade, type Role } from '../../shared/api'

const props = defineProps<{ id?: string; kind?: string }>()
const router = useRouter()
const account = ref<Account>()
const grades = ref<Grade[]>([])
const username = ref('')
const password = ref('')
const gradeId = ref<string | null>(null)
const role = ref<Role>(props.kind === 'admins' ? 'ADMIN' : 'STUDENT')
const error = ref('')
const notice = ref('')
const ready = ref(false)
const busy = ref(false)
const confirmDelete = ref(false)
const list = computed(() => role.value === 'ADMIN' ? '/users/admins' : '/users/students')
const path = computed(() => props.id ? '/admin/users/' + props.id : '/admin/users')

function show(data: Account) {
  account.value = data
  username.value = data.username
  role.value = data.role
  gradeId.value = data.gradeId
}

async function handle(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 401) await router.replace({ name: 'login' })
  else if (cause instanceof ApiError && cause.status === 403) await router.replace({ name: 'denied' })
  else error.value = (cause as Error).message
}

async function load() {
  ready.value = false
  error.value = ''
  try {
    grades.value = await read<Grade[]>('/catalog/grades')
    if (props.id) show(await read<Account>(path.value))
    ready.value = true
  } catch (cause) {
    await handle(cause)
  }
}

async function save() {
  if(busy.value)return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const changes = { username: username.value, role: role.value, gradeId: role.value === 'ADMIN' ? null : gradeId.value }
    if (account.value) {
      show(await write<Account>('PATCH', path.value, { ...changes, expectedVersion: account.value.version }))
      notice.value = '账号已保存。'
    } else {
      const created = await write<Account>('POST', path.value, { ...changes, password: password.value })
      await router.replace('/users/' + created.id + '/edit')
    }
  } catch (cause) {
    await handle(cause)
  } finally {
    password.value = ''
    busy.value = false
  }
}

async function changeStatus(action: 'enable' | 'disable' | 'delete') {
  if (!account.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const body = { expectedVersion: account.value.version }
    if (action === 'delete') {
      await write('DELETE', path.value, body)
      await router.replace(list.value)
    } else {
      show(await write<Account>('POST', path.value + '/' + action, body))
      notice.value = action === 'enable' ? '账号已启用。' : '账号已禁用。'
    }
  } catch (cause) {
    await handle(cause)
  } finally {
    confirmDelete.value = false
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <main>
    <h1>{{ id ? '编辑账号' : '新增账号' }}</h1>
    <RouterLink :to="list">返回列表</RouterLink>
    <p v-if="error" role="alert">{{ error }} <button type="button" @click="load">重新加载</button></p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="!ready && !error" role="status">正在加载账号表单…</p>
    <form v-if="ready" @submit.prevent="save">
      <label>用户名<input v-model="username" autocomplete="off" required maxlength="32"></label>
      <label v-if="!id">初始密码<input v-model="password" type="password" autocomplete="new-password" required></label>
      <label>角色
        <select v-model="role">
          <option value="STUDENT">学生</option>
          <option value="ADMIN">管理员</option>
        </select>
      </label>
      <label v-if="role === 'STUDENT'">年级
        <select v-model="gradeId" required>
          <option disabled :value="null">选择年级</option>
          <option v-for="item in grades" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
      </label>
      <button :disabled="busy">{{ id ? '保存' : '创建' }}</button>
    </form>
    <nav v-if="ready && account" aria-label="账号状态">
      <button v-if="account.status === 'ENABLED'" type="button" :disabled="busy" @click="changeStatus('disable')">禁用账号</button>
      <button v-else type="button" :disabled="busy" @click="changeStatus('enable')">启用账号</button>
      <button v-if="!confirmDelete" type="button" :disabled="busy" @click="confirmDelete = true">删除账号</button>
      <template v-else>
        <p>确认删除该账号？</p>
        <button type="button" :disabled="busy" @click="changeStatus('delete')">确认删除</button>
        <button type="button" :disabled="busy" @click="confirmDelete = false">取消</button>
      </template>
    </nav>
  </main>
</template>
