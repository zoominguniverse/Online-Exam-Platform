<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { read, write, type Account, type Grade, type Role } from './api'

const props = defineProps<{ role: Role; registration?: boolean; destination: string }>()
const router = useRouter()
const username = ref('')
const password = ref('')
const gradeId = ref('')
const grades = ref<Grade[]>([])
const loadingGrades = ref(false)
const gradeError = ref('')
const submitting = ref(false)
const message = ref('')

async function loadGrades() {
  loadingGrades.value = true
  gradeError.value = ''
  try {
    grades.value = await read<Grade[]>('/catalog/grades')
  } catch (error) {
    gradeError.value = (error as Error).message
  } finally {
    loadingGrades.value = false
  }
}

async function submit() {
  submitting.value = true
  message.value = ''
  try {
    const credentials = { username: username.value, password: password.value }
    if (props.registration) {
      await write<Account>('POST', '/auth/register', { ...credentials, gradeId: gradeId.value })
      await router.replace({ name: 'login', query: { registered: '1' } })
    } else {
      const account = await write<Account>('POST', '/auth/login', credentials)
      const previous = localStorage.getItem('exam-active-user')
      if (previous && previous !== account.id) {
        for (const key of Object.keys(localStorage)) if (key.startsWith(`exam-draft:${previous}:`)) localStorage.removeItem(key)
      }
      localStorage.setItem('exam-active-user', account.id)
      if (account.role !== props.role) {
        await router.replace({ name: 'denied' })
        return
      }
      await router.replace(props.destination)
    }
  } catch (error) {
    message.value = (error as Error).message
  } finally {
    password.value = ''
    submitting.value = false
  }
}

onMounted(() => {
  if (props.registration) void loadGrades()
})
</script>

<template>
  <main>
    <h1>{{ registration ? '学生注册' : role === 'ADMIN' ? '管理员登录' : '学生登录' }}</h1>
    <p v-if="$route.query.registered === '1'" role="status">注册成功，请登录。</p>
    <p v-if="message" role="alert">{{ message }}</p>
    <p v-if="loadingGrades" role="status">正在加载年级…</p>
    <p v-if="gradeError" role="alert">{{ gradeError }} <button type="button" @click="loadGrades">重试</button></p>
    <p v-if="registration && !loadingGrades && !gradeError && !grades.length">暂无可用年级。</p>
    <form @submit.prevent="submit">
      <label>用户名<input v-model="username" autocomplete="username" required maxlength="32"></label>
      <label>密码<input v-model="password" type="password" :autocomplete="registration ? 'new-password' : 'current-password'" required></label>
      <label v-if="registration">
        年级
        <select v-model="gradeId" required>
          <option disabled value="">选择年级</option>
          <option v-for="grade in grades" :key="grade.id" :value="grade.id">{{ grade.name }}</option>
        </select>
      </label>
      <button :disabled="submitting || (registration && !grades.length)">
        {{ submitting ? '正在处理…' : registration ? '注册' : '登录' }}
      </button>
    </form>
    <RouterLink v-if="role === 'STUDENT'" :to="{ name: registration ? 'login' : 'register' }">
      {{ registration ? '已有账号，去登录' : '注册学生账号' }}
    </RouterLink>
  </main>
</template>
