<script setup lang="ts">
import { nextTick,onMounted,onUnmounted,ref } from 'vue'
import { init,type ECharts } from 'echarts'
import { read } from '../../shared/api'
type Stats={paperCount:number;questionCount:number;activeUsers:{day:string;count:number}[];newQuestions:{day:string;count:number}[];timeZone:string}
type Metrics={requestCount:number;failureCount:number;failureRate:number;p95Millis:number;databaseUp:boolean;overdueCount:number;deadlineLagSeconds:number;alerts:string[];responsibility:string}
const value=ref<Stats>();const metrics=ref<Metrics>();const error=ref('');const loading=ref(false);const active=ref<HTMLElement>();const questions=ref<HTMLElement>();const charts:ECharts[]=[]
async function load(){loading.value=true;error.value='';try{value.value=await read<Stats>('/admin/statistics');metrics.value=await read<Metrics>('/admin/observability');await nextTick();charts.splice(0).forEach(chart=>chart.dispose());for(const [element,series] of [[active.value,value.value.activeUsers],[questions.value,value.value.newQuestions]] as const){if(element){const chart=init(element);chart.setOption({aria:{enabled:true},tooltip:{trigger:'axis'},xAxis:{type:'category',data:series.map(item=>item.day)},yAxis:{type:'value',minInterval:1},series:[{type:'bar',data:series.map(item=>item.count),itemStyle:{color:'#12549b'}}]});charts.push(chart)}}}catch(cause){error.value=(cause as Error).message}finally{loading.value=false}}
function resize(){charts.forEach(chart=>chart.resize())}
onMounted(()=>{window.addEventListener('resize',resize);void load()});onUnmounted(()=>{window.removeEventListener('resize',resize);charts.forEach(chart=>chart.dispose())})
</script>
<template><main><h1>管理首页</h1><button :disabled="loading" @click="load">刷新统计</button><p v-if="loading" role="status">正在加载统计…</p><p v-if="error" role="alert">{{ error }}</p>
  <template v-if="value"><div class="stat-cards"><p>未归档试卷<strong>{{ value.paperCount }}</strong></p><p>未归档题目<strong>{{ value.questionCount }}</strong></p></div><p>统计时区：{{ value.timeZone }}</p><h2>最近七天活跃用户</h2><div ref="active" class="chart" role="img" aria-label="最近七天每日活跃用户柱状图"></div><details><summary>查看活跃数据表</summary><ul><li v-for="day in value.activeUsers" :key="day.day">{{ day.day }}：{{ day.count }}</li></ul></details><h2>本月每天新增题目</h2><div ref="questions" class="chart" role="img" aria-label="本月每天新增题目柱状图"></div><details><summary>查看新增题目数据表</summary><ul><li v-for="day in value.newQuestions" :key="day.day">{{ day.day }}：{{ day.count }}</li></ul></details></template>
  <section v-if="metrics"><h2>运行状态</h2><p>{{ metrics.databaseUp?'数据库正常':'数据库不可用' }} · 待处理到期答卷 {{ metrics.overdueCount }} 份 · 最大延迟 {{ metrics.deadlineLagSeconds }} 秒</p><p>最近五分钟请求 {{ metrics.requestCount }} 次 · 服务错误 {{ metrics.failureCount }} 次 · p95 {{ metrics.p95Millis.toFixed(0) }} ms</p><p v-if="metrics.alerts.length" role="alert">告警：{{ metrics.alerts.join('、') }} · 负责人：{{ metrics.responsibility }}</p></section>
</main></template>
