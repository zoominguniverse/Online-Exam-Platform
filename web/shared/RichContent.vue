<script setup lang="ts">
import { computed } from 'vue'
import katex from 'katex'
import 'katex/dist/katex.min.css'
const props=defineProps<{ html: string; imagePrefix?: string }>()
const markup=computed(()=>{
  const document=new DOMParser().parseFromString(props.html,'text/html')
  for(const image of document.querySelectorAll('img[data-attachment]')) {
    const id=image.getAttribute('data-attachment') || ''
    if(props.imagePrefix && /^[a-f0-9-]{36}$/.test(id)) image.setAttribute('src',props.imagePrefix+id)
    else image.removeAttribute('src')
  }
  for(const formula of document.querySelectorAll('span[data-tex]')) {
    const tex=formula.getAttribute('data-tex') || ''
    formula.innerHTML=katex.renderToString(tex,{ throwOnError:false,trust:false,strict:'warn',maxExpand:1000 })
  }
  return document.body.innerHTML
})
</script>
<template><div class="rich-content" v-html="markup"></div></template>
<style scoped>
.rich-content { overflow-x:auto; max-width:100%; overflow-wrap:anywhere; }
:deep(img) { max-width:100%; height:auto; }
:deep(table) { width:max-content; min-width:100%; }
:deep(td),:deep(th) { min-width:5rem; }
</style>
