<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { read } from './api'
import { time,type Page } from './exam'
const props=defineProps<{admin?:boolean}>()
type Event={id:string;requestId:string;actorId:string;module:string;action:string;targetId:string;result:string;createdAt:string}
const items=ref<Event[]>([]);const loading=ref(false);const error=ref('');const page=ref(1);const total=ref(0);const request=ref('');const actor=ref('');const action=ref('')
async function load(next=1) {loading.value=true;error.value='';items.value=[];try {
  const query=new URLSearchParams({page:String(next)});if(props.admin){if(request.value)query.set('requestId',request.value);if(actor.value)query.set('actorId',actor.value);if(action.value)query.set('action',action.value)}
  const value=await read<Page<Event>>((props.admin?'/admin/audit-events':'/me/activities')+'?'+query);items.value=value.items;total.value=value.total;page.value=value.page
}catch(cause){error.value=(cause as Error).message}finally{loading.value=false}}
onMounted(()=>load())
</script>
<template><main><h1>{{ admin?'操作日志':'个人动态' }}</h1><form v-if="admin" @submit.prevent="load()"><label>请求标识<input v-model="request"></label><label>用户标识<input v-model="actor"></label><label>操作<input v-model="action" placeholder="例如 Attempt.submit"></label><button :disabled="loading">筛选日志</button></form>
  <p v-if="loading" role="status">正在加载动态…</p><p v-else-if="error" role="alert">{{ error }} <button @click="load(page)">重试</button></p><p v-else-if="!items.length">暂无动态。</p>
  <div v-else class="table-scroll"><table><thead><tr><th>时间（香港）</th><th>操作</th><th>结果</th><th v-if="admin">用户</th><th>目标</th><th>请求标识</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td>{{ time(item.createdAt) }}</td><td>{{ item.action }}</td><td>{{ item.result==='SUCCESS'?'成功':item.result }}</td><td v-if="admin">{{ item.actorId||'系统' }}</td><td>{{ item.targetId||'—' }}</td><td>{{ item.requestId }}</td></tr></tbody></table></div>
  <nav aria-label="动态分页"><button :disabled="loading||page<=1" @click="load(page-1)">上一页</button><span>共 {{ total }} 条</span><button :disabled="loading||page*20>=total" @click="load(page+1)">下一页</button></nav>
</main></template>
