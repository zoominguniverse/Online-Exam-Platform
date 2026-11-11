<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, read, write } from '../../shared/api'

type Paper = {
  id: string; name: string; type: 'FIXED' | 'TIMED'; durationMinutes: number
  status: 'OPEN' | 'UPCOMING' | 'CLOSED'; opensAt: string | null; closesAt: string | null
}
const router = useRouter()
const statusNames = { OPEN: '已开放', UPCOMING: '尚未开放', CLOSED: '已结束' }
const time = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Hong_Kong', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
const items = ref<Paper[]>([])
const page = ref(1)
const total = ref(0)
const busy = ref(false)
const error = ref('')
const starting = ref(false)
async function start(id: string) {
  if(starting.value)return
  starting.value=true
  error.value = ''
  try {
    const attempt = await write<{ id: string }>('POST', `/me/papers/${id}/attempts`)
    await router.push('/attempts/' + attempt.id)
  } catch (cause) { error.value = (cause as Error).message }
  finally {starting.value=false}
}

async function load(next = page.value) {
  busy.value = true
  error.value = ''
  try {
    const result = await read<{ items: Paper[]; total: number; page: number }>('/me/papers?page=' + next)
    items.value = result.items
    page.value = result.page
    total.value = result.total
  } catch (cause) {
    items.value = []
    if (cause instanceof ApiError && cause.status === 401) await router.replace({ name: 'login' })
    else error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <main>
    <h1>练习试卷</h1>
    <RouterLink to="/home">返回学生中心</RouterLink>
    <p v-if="busy" role="status">正在加载试卷…</p>
    <p v-else-if="error" role="alert">{{ error }} <button type="button" @click="load()">重试</button></p>
    <p v-else-if="!items.length">暂无练习试卷。</p>
    <ul v-else>
      <li v-for="item in items" :key="item.id">
        <h2>{{ item.name }}</h2>
        <p>{{ item.type === 'FIXED' ? '固定卷' : '时段卷' }} · {{ item.durationMinutes }} 分钟 · {{ statusNames[item.status] }}</p>
        <p v-if="item.opensAt && item.closesAt">
          {{ time.format(new Date(item.opensAt)) }} 至 {{ time.format(new Date(item.closesAt)) }}（香港时间）
        </p>
        <button type="button" :disabled="starting || item.status !== 'OPEN'" @click="start(item.id)">开始作答</button>
      </li>
    </ul>
    <nav aria-label="试卷分页">
      <button type="button" :disabled="busy || page <= 1" @click="load(page - 1)">上一页</button>
      <button type="button" :disabled="busy || page * 20 >= total" @click="load(page + 1)">下一页</button>
    </nav>
  </main>
</template>
