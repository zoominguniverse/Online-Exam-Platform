<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ id: string; version: number; html: string }>()
const markup = computed(() => {
  const doc = new DOMParser().parseFromString(props.html, 'text/html')
  for (const image of doc.querySelectorAll('img[data-attachment]')) {
    image.src = '/api/v1/admin/questions/' + props.id + '/versions/' + props.version
      + '/attachments/' + image.getAttribute('data-attachment')
  }
  return doc.body.innerHTML
})
</script>

<template>
  <div class="table-scroll" v-html="markup"></div>
</template>

<style scoped>
:deep(img) { max-width: 100%; height: auto; }
</style>
