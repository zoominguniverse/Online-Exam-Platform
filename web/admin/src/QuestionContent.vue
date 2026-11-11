<script setup lang="ts">
import RichContent from '../../shared/RichContent.vue'

const props = defineProps<{ id: string; version: number; html: string }>()
</script>

<template>
  <RichContent :html="html" :image-prefix="`/api/v1/admin/questions/${props.id}/versions/${props.version}/attachments/`" />
</template>

<style scoped>
:deep(img) { max-width: 100%; height: auto; }
</style>
