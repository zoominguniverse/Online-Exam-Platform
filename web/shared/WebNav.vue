<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ref } from 'vue'
import { write } from './api'
defineProps<{ admin?: boolean }>()
const router=useRouter();const error=ref('');const busy=ref(false)
async function logout() {
  const generation=localStorage.getItem('exam-auth-epoch')
  busy.value=true;error.value=''
  try {
    await write('POST','/auth/logout')
    if(generation!==localStorage.getItem('exam-auth-epoch'))return
    const owner=localStorage.getItem('exam-active-user')
    if(owner) for(const key of Object.keys(localStorage)) if(key.startsWith(`exam-draft:${owner}:`)) localStorage.removeItem(key)
    localStorage.removeItem('exam-active-user');localStorage.removeItem('exam-auth-epoch');await router.replace('/login')
  } catch(cause) { error.value=(cause as Error).message } finally { busy.value=false }
}
</script>
<template>
  <header class="site-nav"><nav :aria-label="admin?'管理导航':'学生导航'">
    <template v-if="admin"><RouterLink to="/dashboard">管理首页</RouterLink><RouterLink to="/users/students">账号</RouterLink><RouterLink to="/subjects">学科</RouterLink><RouterLink to="/questions">题库</RouterLink><RouterLink to="/papers">试卷</RouterLink><RouterLink to="/tasks">任务</RouterLink><RouterLink to="/attempts">成绩</RouterLink><RouterLink to="/activities">操作日志</RouterLink></template>
    <template v-else><RouterLink to="/home">学生中心</RouterLink><RouterLink to="/papers">练习</RouterLink><RouterLink to="/tasks">年级任务</RouterLink><RouterLink to="/records">考试记录</RouterLink><RouterLink to="/mistakes">错题本</RouterLink><RouterLink to="/activities">个人动态</RouterLink></template>
    <RouterLink to="/messages">消息</RouterLink><RouterLink to="/profile">个人资料</RouterLink><button type="button" :disabled="busy" @click="logout">退出当前会话</button>
  </nav><p v-if="error" role="alert">{{ error }}</p></header>
</template>
