<script setup lang="ts">
import { computed,onMounted,ref } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError,operation,read } from './api'
import RichContent from './RichContent.vue'
import { displayAnswer,reference,statuses,time,type Review } from './exam'
const props=defineProps<{id:string;admin?:boolean}>();const route=useRoute()
const value=ref<Review>();const error=ref('');const loading=ref(true);const busy=ref(false);const confirmed=ref(false)
const scores=ref<Record<string,string>>({});const pending=ref<{key:string;body:object}>()
const canGrade=computed(()=>!props.admin&&value.value?.attempt.status==='WAIT_SELF_GRADE')
function apply(next:Review) { value.value=next;scores.value=Object.fromEntries(next.items.filter(item=>item.subjective).map(item=>[item.questionId,item.empty?'0.00':item.awardedScore||'0.00'])) }
async function load() { loading.value=true;error.value='';try {apply(await read<Review>(props.admin?'/admin/attempts/'+props.id:'/me/attempts/'+props.id+'/review'))}catch(cause){error.value=(cause as Error).message}finally{loading.value=false} }
async function grade() {
  if(!value.value||busy.value) return;busy.value=true;error.value=''
  const payload={expectedVersion:value.value.attempt.version,scores:value.value.items.filter(item=>item.subjective).map(item=>({questionId:item.questionId,score:String(scores.value[item.questionId])}))}
  if(!pending.value)pending.value={key:crypto.randomUUID(),body:payload}
  try { apply(await operation<Review>('/me/attempts/'+props.id+'/self-grade',pending.value.key,pending.value.body));confirmed.value=false;pending.value=undefined }
  catch(cause) { error.value=(cause as Error).message;try {const latest=await read<Review>('/me/attempts/'+props.id+'/review');if(latest.attempt.status==='COMPLETED'){apply(latest);pending.value=undefined}}catch{};if(cause instanceof ApiError&&cause.status>=400&&cause.status<500)pending.value=undefined }
  finally {busy.value=false}
}
onMounted(load)
</script>
<template><main>
  <h1>答卷结果与回看</h1><RouterLink :to="admin?'/attempts':'/records'">返回记录</RouterLink>
  <p v-if="route.query.unsynced==='1'" role="alert">已截止，未同步内容未计入服务器答案；以下为已确认冻结的结果。</p>
  <p v-if="loading" role="status">正在加载答卷…</p><p v-if="error" role="alert">{{ error }} <button :disabled="busy" @click="load">重试加载</button></p>
  <template v-if="value"><h2>{{ value.metadata.paperName }}</h2><p v-if="admin">学生：{{ value.metadata.username }}</p><p>开始：{{ time(value.metadata.startedAt) }} · 用时 {{ value.metadata.elapsedSeconds }} 秒</p><p>{{ statuses[value.attempt.status] }} · {{ value.gradingSource==='SELF'?'自行批改（SELF）':'自动判分' }}<span v-if="value.metadata.confirmedAt"> · 确认于 {{ time(value.metadata.confirmedAt) }}</span></p>
    <p>客观成绩：{{ value.attempt.automaticScore ?? '—' }} · {{ value.attempt.finalScore===null?'暂定成绩，待自行批改':'最终成绩：'+value.attempt.finalScore }}</p>
    <p>截止：{{ time(value.attempt.deadlineAt) }} · 冻结：{{ time(value.attempt.frozenAt) }}（香港时间）</p>
    <form class="review-form" @submit.prevent="grade"><fieldset :disabled="busy"><legend>{{ canGrade?'一次确认全部主观题评分':'历史答卷' }}</legend>
      <section v-for="item in value.items" :key="item.questionId"><h2>第 {{ item.position }} 题 · {{ item.score }} 分</h2>
        <RichContent :html="item.stemHtml" :image-prefix="`/api/v1/${admin?'admin':'me'}/attempts/${id}/questions/${item.questionId}/attachments/`" />
        <p>作答：{{ displayAnswer(item,item.answer) }}</p><p>参考答案：{{ reference(item) }}</p>
        <RichContent v-if="item.explanationHtml" :html="item.explanationHtml" :image-prefix="`/api/v1/${admin?'admin':'me'}/attempts/${id}/questions/${item.questionId}/attachments/`" />
        <label v-if="canGrade&&item.subjective">第 {{ item.position }} 题评分（满分 {{ item.score }}）<input v-model="scores[item.questionId]" :disabled="item.empty||Boolean(pending)" type="number" min="0" :max="item.score" step="0.01" required></label>
        <p v-if="canGrade&&item.empty&&item.subjective">空答案固定零分。</p><p v-else>得分：{{ item.awardedScore ?? '待自行批改' }}<span v-if="item.subjective"> · 自行评分</span></p>
      </section>
      <template v-if="canGrade"><p>确认后分数不可修改，客观分数由系统确定。</p><label class="check-label"><input v-model="confirmed" :disabled="Boolean(pending)" type="checkbox" required>我已核对全部主观题评分</label><button :disabled="busy||(!confirmed&&!pending)">{{ pending?'重试原批改确认':'确认自行批改' }}</button></template>
    </fieldset></form>
  </template>
</main></template>
