<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { read } from '../../shared/api'
import { displayAnswer,reference,type ReviewItem,type Page } from '../../shared/exam'
import RichContent from '../../shared/RichContent.vue'
const props=defineProps<{id?:string}>()
type Mistake={questionId:string;attemptId:string;item:ReviewItem;subject:{subjectId:string;subjectName:string}}
const items=ref<Mistake[]>([]);const page=ref(1);const total=ref(0);const loading=ref(false);const error=ref('');const subject=ref('');const subjects=ref<{id:string;name:string}[]>([])
async function load(next=1) { loading.value=true;error.value='';items.value=[];try {
  if(props.id) items.value=[await read<Mistake>('/me/mistakes/'+props.id)]
  else {const value=await read<Page<Mistake>>('/me/mistakes?'+new URLSearchParams({page:String(next),...(subject.value?{subjectId:subject.value}:{})}));items.value=value.items;page.value=value.page;total.value=value.total}
}catch(cause){error.value=(cause as Error).message}finally{loading.value=false} }
onMounted(async()=>{try{subjects.value=(await read<Page<{id:string;name:string}>>('/me/subjects?pageSize=100')).items}catch{}await load()})
</script>
<template><main><h1>错题本</h1><form v-if="!id" @submit.prevent="load()"><label>学科<select v-model="subject"><option value="">全部学科</option><option v-for="item in subjects" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><button :disabled="loading">筛选错题</button></form>
  <p v-if="loading" role="status">正在加载错题…</p><p v-else-if="error" role="alert">{{ error }} <button @click="load(page)">重试</button></p><p v-else-if="!items.length">暂无错题。完成练习后可在这里复习。<RouterLink to="/papers">开始练习</RouterLink></p>
  <section v-for="mistake in items" :key="mistake.questionId"><h2>第 {{ mistake.item.position }} 题 · {{ mistake.item.awardedScore }} / {{ mistake.item.score }}</h2><p>{{ mistake.subject.subjectName }} · {{ {SINGLE:'单选题',MULTIPLE:'多选题',TRUE_FALSE:'判断题',GAP:'填空题',SHORT:'简答题'}[mistake.item.type] }}</p><RichContent :html="mistake.item.stemHtml" :image-prefix="`/api/v1/me/attempts/${mistake.attemptId}/questions/${mistake.questionId}/attachments/`" /><p>作答：{{ displayAnswer(mistake.item,mistake.item.answer) }}</p><p>参考答案：{{ reference(mistake.item) }}</p><RouterLink :to="'/attempts/'+mistake.attemptId+'/review'">查看最近错误答卷</RouterLink></section>
  <nav v-if="!id" aria-label="错题分页"><button :disabled="loading||page<=1" @click="load(page-1)">上一页</button><span>共 {{ total }} 题</span><button :disabled="loading||page*20>=total" @click="load(page+1)">下一页</button></nav>
</main></template>
