<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { verifySession } from '../context'
import { router } from '../router'
import { errorText } from '../api/client'
import { safeReturn } from '../shared/format'
import ErrorNotice from '../components/ErrorNotice.vue'
const route = useRoute()
const retrying = ref(false)
const error = ref('')
const title = computed(() => route.path === '/session' ? '暂时无法验证登录状态' : route.path === '/forbidden' ? '当前账号无权访问此工作区' : '这个页面暂不存在')
async function retry() { if (retrying.value) return; retrying.value = true; error.value = ''; try { if (await verifySession(true)) await router.replace(safeReturn(route.query.returnTo)); else await router.replace('/login') } catch(e) { error.value = errorText(e) } finally { retrying.value = false } }
</script>
<template><section class="surface status-page"><span class="eyebrow">CAMPUSLIFE</span><h1>{{ title }}</h1><p class="muted">{{ route.path === '/session' ? '请确认后端已启动。为避免展示未经核实的个人信息，当前暂停进入受保护页面。' : '你可以返回商家目录，或使用正确的账号重新登录。' }}</p><ErrorNotice :message="error" /><el-button v-if="route.path === '/session'" type="primary" :loading="retrying" @click="retry">重试会话验证</el-button><RouterLink to="/shops" class="text-link">返回商家目录 →</RouterLink></section></template>
