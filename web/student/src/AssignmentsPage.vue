<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, read } from '../../shared/api'

type Task = {
  id: string; name: string; state: 'PUBLISHED' | 'ARCHIVED'
  papers: { paperId: string; name: string; durationMinutes: number; status: string }[]
}
const props = defineProps<{ id?: string }>()
const router = useRouter()
const items = ref<Task[]>([])
const page = ref(1)
const total = ref(0)
const busy = ref(false)
const error = ref('')

async function load(next = page.value) {
  busy.value = true
  error.value = ''
  items.value = []
  try {
    if (props.id) items.value = [await read<Task>('/me/tasks/' + props.id)]
    else {
      const result = await read<{ items: Task[]; total: number; page: number }>('/me/tasks?page=' + next)
      items.value = result.items
      total.value = result.total
      page.value = result.page
    }
  } catch (cause) {
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
    <h1>{{ id ? '任务详情' : '我的年级任务' }}</h1>
    <RouterLink :to="id ? '/tasks' : '/home'">{{ id ? '返回任务列表' : '返回学生中心' }}</RouterLink>
    <p v-if="busy" role="status">正在加载任务…</p>
    <p v-else-if="error" role="alert">{{ error }} <button type="button" @click="load()">重试</button></p>
    <p v-else-if="!items.length">暂无年级任务。</p>
    <article v-for="task in items" :key="task.id">
      <h2><RouterLink v-if="!id" :to="'/tasks/' + task.id">{{ task.name }}</RouterLink><template v-else>{{ task.name }}</template></h2>
      <p v-if="task.state === 'ARCHIVED'">已归档</p>
      <ol v-if="id">
        <li v-for="paper in task.papers" :key="paper.paperId">
          {{ paper.name }} · {{ paper.durationMinutes }} 分钟 · {{ paper.status === 'NOT_STARTED' ? '未开始' : paper.status }}
        </li>
      </ol>
    </article>
    <nav v-if="!id" aria-label="任务分页">
      <button type="button" :disabled="busy || page <= 1" @click="load(page - 1)">上一页</button>
      <button type="button" :disabled="busy || page * 20 >= total" @click="load(page + 1)">下一页</button>
    </nav>
  </main>
</template>
