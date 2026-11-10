<script setup lang="ts">
import { onMounted, ref } from 'vue'

defineProps<{ title: string }>()
const version = ref('')
const message = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  message.value = ''
  version.value = ''
  try {
    const response = await fetch('/api/v1/version', { signal: AbortSignal.timeout(5000) })
    const payload = await response.json()
    if (!response.ok || payload.code !== 0 || typeof payload.data?.apiVersion !== 'string') {
      throw new Error('Unexpected version response')
    }
    version.value = payload.data.apiVersion
  } catch {
    message.value = '暂时无法连接考试服务，请稍后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <main>
    <h1>{{ title }}</h1>
    <p v-if="loading" role="status">正在连接考试服务…</p>
    <p v-else-if="message" role="alert">{{ message }}</p>
    <p v-else-if="version">API 版本 {{ version }}</p>
    <p><RouterLink :to="{ name: 'login' }">登录</RouterLink></p>
    <button v-if="message" type="button" @click="load">重试</button>
  </main>
</template>

<style scoped>
main { max-width: 42rem; margin: 3rem auto; padding: 0 1rem; color: #182634; font-family: system-ui, sans-serif; }
button { padding: .5rem .75rem; font: inherit; }
</style>
