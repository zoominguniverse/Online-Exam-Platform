<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { read,write } from './api'
import { time } from './exam'
type Profile={ id:string;username:string;role:string;name:string;phone:string;birthday:string|null;avatarId:string|null;version:number;createdAt:string }
const profile=ref<Profile>();const name=ref('');const phone=ref('');const birthday=ref('');const busy=ref(false);const error=ref('');const notice=ref('')
function apply(value:Profile) { profile.value=value;name.value=value.name;phone.value=value.phone;birthday.value=value.birthday||'' }
async function load() { busy.value=true;error.value='';try{apply(await read<Profile>('/me/profile'))}catch(cause){error.value=(cause as Error).message}finally{busy.value=false} }
async function save() { if(!profile.value)return;busy.value=true;error.value='';notice.value='';try {apply(await write<Profile>('PATCH','/me/profile',{expectedVersion:profile.value.version,name:name.value,phone:phone.value,...(profile.value.role==='STUDENT'?{birthday:birthday.value||null}:{})}));notice.value='资料已保存。'}catch(cause){error.value=(cause as Error).message}finally{busy.value=false} }
async function avatar(event:Event) { const input=event.target as HTMLInputElement;const file=input.files?.[0];input.value='';if(!file)return;busy.value=true;error.value='';try{const data=new FormData();data.append('file',file);apply(await write<Profile>('POST','/me/avatar',data));notice.value='头像已保存。'}catch(cause){error.value=(cause as Error).message}finally{busy.value=false} }
onMounted(load)
</script>
<template><main><h1>个人资料</h1><p v-if="busy" role="status">正在处理…</p><p v-if="error" role="alert">{{ error }} <button :disabled="busy" @click="load">重新加载</button></p><p v-if="notice" role="status">{{ notice }}</p>
  <template v-if="profile"><p>用户名：{{ profile.username }}</p><p>创建时间：{{ time(profile.createdAt) }}（香港时间）</p><img v-if="profile.avatarId" :key="profile.version" :src="'/api/v1/me/avatar/image'" alt="我的头像" class="avatar">
    <form @submit.prevent="save"><fieldset :disabled="busy"><legend>可编辑资料</legend><label>姓名<input v-model="name" maxlength="100" autocomplete="name"></label><label>手机号<input v-model="phone" maxlength="32" autocomplete="tel" type="tel"></label><label v-if="profile.role==='STUDENT'">生日<input v-model="birthday" type="date" min="1900-01-01"></label><button>保存资料</button></fieldset></form>
    <label>更新头像（PNG、JPEG、WebP，最多 5 MiB）<input type="file" accept="image/png,image/jpeg,image/webp" :disabled="busy" @change="avatar"></label>
    <p>年级与账号状态由管理员维护。</p>
  </template>
</main></template>
