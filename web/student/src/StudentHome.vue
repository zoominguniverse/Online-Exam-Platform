<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, read, write, type Account } from '../../shared/api'

const router = useRouter()
const account = ref<Account>()
const error = ref('')
const busy = ref(false)

async function load() {
  error.value = ''
  try {
    account.value = await read<Account>('/me')
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 401) await router.replace({ name: 'login' })
    else error.value = (cause as Error).message
  }
}

async function logout() {
  busy.value = true
  error.value = ''
  try {
    await write('POST', '/auth/logout')
    await router.replace({ name: 'login' })
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 401) await router.replace({ name: 'login' })
    else error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <main>
    <h1>学生中心</h1>
    <p v-if="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <p v-else-if="!account" role="status">正在加载账号…</p>
    <p v-if="account">已登录：{{ account.username }}</p>
    <p v-if="account"><RouterLink to="/papers">练习试卷</RouterLink></p>
    <p v-if="account"><RouterLink to="/tasks">我的年级任务</RouterLink></p>
    <button v-if="account" type="button" :disabled="busy" @click="logout">退出登录</button>
  </main>
</template>
