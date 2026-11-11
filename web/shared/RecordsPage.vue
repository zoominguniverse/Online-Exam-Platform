<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { read } from './api'
import { statuses,time,type Page } from './exam'
const props=defineProps<{ admin?: boolean }>()
type RecordItem={ id:string;ownerId:string;username:string;paperName:string;status:string;startedAt:string;automaticScore:string|null;finalScore:string|null;gradingSource:string;elapsedSeconds:number }
const items=ref<RecordItem[]>([]);const subjects=ref<{id:string;name:string}[]>([]);const subject=ref('');const status=ref('');const owner=ref('')
const page=ref(1);const total=ref(0);const loading=ref(false);const error=ref('')
async function load(next=1) {
  loading.value=true;error.value='';items.value=[]
  try {
    const query=new URLSearchParams({page:String(next)})
    if(subject.value) query.set('subjectId',subject.value)
    if(status.value) query.set('status',status.value)
    if(props.admin&&owner.value) query.set('ownerId',owner.value)
    const value=await read<Page<RecordItem>>((props.admin?'/admin':'/me')+'/attempts?'+query)
    items.value=value.items;total.value=value.total;page.value=value.page
  } catch(cause) { error.value=(cause as Error).message } finally { loading.value=false }
}
onMounted(async()=>{
  try { subjects.value=(await read<Page<{id:string;name:string}>>((props.admin?'/admin':'/me')+'/subjects?pageSize=100')).items } catch { /* Record filtering remains available on retry. */ }
  await load()
})
</script>
<template><main>
  <h1>{{ admin?'答卷与成绩':'考试记录' }}</h1>
  <form @submit.prevent="load()"><label>学科<select v-model="subject"><option value="">全部学科</option><option v-for="item in subjects" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
    <label>状态<select v-model="status"><option value="">全部状态</option><option v-for="(name,key) in statuses" v-show="key!=='NOT_STARTED'" :key="key" :value="key">{{ name }}</option></select></label>
    <label v-if="admin">学生标识<input v-model="owner" placeholder="可从学生列表复制标识"></label><button :disabled="loading">筛选记录</button></form>
  <p v-if="loading" role="status">正在加载记录…</p><p v-else-if="error" role="alert">{{ error }} <button @click="load(page)">重试</button></p>
  <p v-else-if="!items.length">暂无符合条件的记录。<RouterLink v-if="!admin" to="/papers">开始练习</RouterLink></p>
  <div v-else class="table-scroll"><table><thead><tr><th>试卷</th><th v-if="admin">学生</th><th>状态</th><th>成绩</th><th>开始时间（香港）</th><th>用时</th><th>操作</th></tr></thead>
    <tbody><tr v-for="item in items" :key="item.id"><td>{{ item.paperName }}</td><td v-if="admin">{{ item.username }}</td><td>{{ statuses[item.status] }}</td><td>{{ item.finalScore ?? ((item.automaticScore ?? '—')+'（暂定）') }}<span v-if="item.gradingSource==='SELF'"> · 自行批改</span></td><td>{{ time(item.startedAt) }}</td><td>{{ item.elapsedSeconds }} 秒</td><td><RouterLink :to="admin?'/attempts/'+item.id:item.status==='IN_PROGRESS'?'/attempts/'+item.id:'/attempts/'+item.id+'/review'">{{ item.status==='IN_PROGRESS'&&!admin?'继续答题':'查看答卷' }}</RouterLink></td></tr></tbody></table></div>
  <nav aria-label="记录分页"><button :disabled="loading||page<=1" @click="load(page-1)">上一页</button><span>第 {{ page }} 页 · 共 {{ total }} 份</span><button :disabled="loading||page*20>=total" @click="load(page+1)">下一页</button></nav>
</main></template>
