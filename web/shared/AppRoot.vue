<script setup lang="ts">
import { onMounted,onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import WebNav from './WebNav.vue'
import { validateField,clearField } from './form-validation'
defineProps<{admin?:boolean}>()
const router=useRouter()
function changed(event:StorageEvent) { if(event.key==='exam-active-user'&&event.oldValue!==event.newValue&&router.currentRoute.value.meta.protected) void router.replace('/login') }
function expired() {if(!router.currentRoute.value.meta.protected)return;const owner=localStorage.getItem('exam-active-user');if(owner)for(const key of Object.keys(localStorage))if(key.startsWith(`exam-draft:${owner}:`))localStorage.removeItem(key);localStorage.removeItem('exam-active-user');localStorage.removeItem('exam-auth-epoch');void router.replace('/login')}
onMounted(()=>{window.addEventListener('storage',changed);window.addEventListener('exam-session-expired',expired)});onUnmounted(()=>{window.removeEventListener('storage',changed);window.removeEventListener('exam-session-expired',expired)})
</script>
<template><RouterView v-slot="{Component,route}"><WebNav v-if="route.meta.protected" :admin="admin" /><component :is="Component" :key="route.path" @focusout="validateField" @invalid.capture="validateField" @input="clearField" /></RouterView></template>
