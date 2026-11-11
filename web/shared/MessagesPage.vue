<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError,operation,read,write,type Account } from './api'
import { time,type Page } from './exam'
const props=defineProps<{admin?:boolean;id?:string;compose?:boolean}>();const router=useRouter()
type Message={id:string;title:string;body:string;sentAt:string;readAt:string|null;recipientCount?:number;readCount?:number}
const items=ref<Message[]>([]);const message=ref<Message>();const recipients=ref<{id:string;username:string;readAt:string|null}[]>([])
const loading=ref(false);const busy=ref(false);const error=ref('');const page=ref(1);const total=ref(0)
const title=ref('');const body=ref('');const users=ref<Account[]>([]);const userPage=ref(1);const userTotal=ref(0);const role=ref('STUDENT');const selected=ref<string[]>([]);const pending=ref<{key:string;body:object}>()
async function load(next=1) { loading.value=true;error.value='';try {
  if(props.compose) await loadUsers(1)
  else if(props.id) {
    if(props.admin) { const value=await read<{message:Message;recipients:typeof recipients.value}>('/admin/messages/'+props.id);message.value=value.message;recipients.value=value.recipients }
    else {message.value=await read<Message>('/me/messages/'+props.id);if(!message.value.readAt) message.value=await write<Message>('PUT','/me/messages/'+props.id+'/read')}
  } else {const value=await read<Page<Message>>((props.admin?'/admin':'/me')+'/messages?page='+next);items.value=value.items;page.value=value.page;total.value=value.total}
}catch(cause){error.value=(cause as Error).message}finally{loading.value=false} }
async function loadUsers(next:number) { const value=await read<Page<Account>>('/admin/users?'+new URLSearchParams({role:role.value,page:String(next),pageSize:'50'}));users.value=value.items;userPage.value=value.page;userTotal.value=value.total }
async function send() { busy.value=true;error.value='';if(!pending.value)pending.value={key:crypto.randomUUID(),body:{title:title.value,body:body.value,recipientIds:[...selected.value]}};try {const value=await operation<{message:Message}>('/admin/messages',pending.value.key,pending.value.body);await router.replace('/messages/'+value.message.id)}catch(cause){error.value=(cause as Error).message;if(cause instanceof ApiError&&cause.status>=400&&cause.status<500)pending.value=undefined}finally{busy.value=false} }
onMounted(()=>load())
</script>
<template><main><h1>{{ compose?'发送站内消息':id?'消息详情':admin?'已发送消息':'消息中心' }}</h1>
  <RouterLink v-if="id||compose" to="/messages">返回消息列表</RouterLink><RouterLink v-else-if="admin" to="/messages/new">发送新消息</RouterLink>
  <p v-if="loading" role="status">正在加载消息…</p><p v-if="error" role="alert">{{ error }} <button :disabled="busy" @click="load(page)">重试</button></p>
  <form v-if="compose" @submit.prevent="send"><fieldset :disabled="busy||loading"><legend>消息内容</legend><label>标题<input v-model="title" :disabled="Boolean(pending)" maxlength="200" required></label><label>正文<textarea v-model="body" :disabled="Boolean(pending)" maxlength="10000" rows="6" required></textarea></label><label>账号类别<select v-model="role" :disabled="Boolean(pending)" @change="loadUsers(1)"><option value="STUDENT">学生</option><option value="ADMIN">管理员</option></select></label>
    <p>已选择 {{ selected.length }} / 500 位收件人；禁用账号不可发送。</p><label v-for="user in users" :key="user.id" class="check-label"><input v-model="selected" type="checkbox" :value="user.id" :disabled="Boolean(pending)||user.status!=='ENABLED'||(!selected.includes(user.id)&&selected.length>=500)">{{ user.username }} {{ user.status==='ENABLED'?'':'（已禁用）' }}</label>
    <nav aria-label="收件人分页"><button type="button" :disabled="userPage<=1" @click="loadUsers(userPage-1)">收件人上一页</button><button type="button" :disabled="userPage*50>=userTotal" @click="loadUsers(userPage+1)">收件人下一页</button></nav><button :disabled="!selected.length">{{ busy?'正在发送…':pending?'重试原发送请求':'确认发送消息' }}</button>
  </fieldset></form>
  <article v-if="message"><h2>{{ message.title }}</h2><p>{{ time(message.sentAt) }}（香港时间）</p><p class="message-body">{{ message.body }}</p><p v-if="admin">收件 {{ message.recipientCount }} 人 · 已读 {{ message.readCount }} 人</p><p v-else>已读：{{ time(message.readAt) }}</p>
    <ul v-if="admin"><li v-for="recipient in recipients" :key="recipient.id">{{ recipient.username }} · {{ recipient.readAt?'已读 '+time(recipient.readAt):'未读' }}</li></ul>
  </article>
  <template v-if="!compose&&!id&&!loading&&!error"><p v-if="!items.length">暂无消息。</p><ul v-else><li v-for="item in items" :key="item.id"><RouterLink :to="'/messages/'+item.id">{{ item.title }}</RouterLink> · {{ time(item.sentAt) }} · {{ admin?'已读 '+item.readCount+' / '+item.recipientCount:item.readAt?'已读':'未读' }}</li></ul>
    <nav aria-label="消息分页"><button :disabled="page<=1" @click="load(page-1)">上一页</button><span>共 {{ total }} 条</span><button :disabled="page*20>=total" @click="load(page+1)">下一页</button></nav>
  </template>
</main></template>
