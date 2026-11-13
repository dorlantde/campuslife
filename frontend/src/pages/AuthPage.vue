<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api, login, session } from '../context'
import { router } from '../router'
import { errorText } from '../api/client'
import { safeReturn } from '../shared/format'
import ErrorNotice from '../components/ErrorNotice.vue'
const route = useRoute()
const registering = computed(() => route.path === '/register')
const form = reactive({ username: '', password: '', nickname: '' })
const busy = ref(false)
const error = ref('')
const success = ref('')
async function submit() {
  if (busy.value) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    if (registering.value) {
      await api('/api/v1/auth/register', { method: 'POST', auth: false, body: { ...form } })
      form.password = ''
      success.value = '学生账号注册成功，请使用刚设置的密码登录。'
      await router.replace({ path: '/login', query: { ...route.query, registered: '1' } })
    } else {
      await login(form.username, form.password)
      form.password = ''
      await router.replace(safeReturn(route.query.returnTo))
    }
  } catch (e) { error.value = errorText(e) }
  finally { form.password = ''; busy.value = false }
}
</script>
<template>
  <section class="auth-layout">
    <div class="auth-story"><span class="eyebrow">HELLO, CAMPUS</span><h1>日常小事，<br>在校园里办好。</h1><p>发现身边的服务，<br>让每一次到店都有安排。</p><div class="story-note">理发预约 · 校园打印 · 真实评价</div><div class="campus-art" aria-hidden="true"><div class="sun"></div><div class="building b1"></div><div class="building b2"></div><div class="building b3"></div><div class="ground"></div></div></div>
    <div class="surface auth-form">
      <span class="eyebrow">CAMPUSLIFE ACCOUNT</span><h2>{{ registering ? '创建学生账号' : '欢迎回来' }}</h2><p class="muted">{{ registering ? '注册后使用统一登录入口进入校园生活。' : '学生、商家与管理员使用同一登录入口。' }}</p>
      <template v-if="session.current.value && session.verified.value"><el-alert title="当前已有登录账号。切换账号请先点击右上角退出。" type="info" :closable="false" /><RouterLink to="/workspace" class="text-link">进入我的工作区 →</RouterLink></template>
      <form v-else @submit.prevent="submit" class="stack-form">
        <ErrorNotice :message="error" /><el-alert v-if="success || (!registering && route.query.registered === '1')" :title="success || '学生账号注册成功，请使用刚设置的密码登录。'" type="success" :closable="false" role="status" />
        <label for="username">用户名</label><el-input id="username" v-model="form.username" name="username" autocomplete="username" :maxlength="32" placeholder="4～32 位字母、数字或下划线" :disabled="busy" required />
        <template v-if="registering"><label for="nickname">昵称</label><el-input id="nickname" v-model="form.nickname" name="nickname" autocomplete="nickname" placeholder="在评价中展示的名字" :disabled="busy" required /></template>
        <label for="password">密码</label><el-input id="password" v-model="form.password" name="password" type="password" :autocomplete="registering ? 'new-password' : 'current-password'" show-password placeholder="请输入密码" :disabled="busy" required />
        <p v-if="registering" class="field-help">至少 8 个字符，UTF-8 不超过 72 字节；密码不会自动去除空白。</p>
        <el-button type="primary" size="large" native-type="submit" :loading="busy" class="full-width">{{ registering ? '注册学生账号' : '登录' }}</el-button>
        <p class="form-switch"><RouterLink :to="{ path: registering ? '/login' : '/register', query: route.query }">{{ registering ? '已有账号？去登录' : '还没有学生账号？立即注册' }}</RouterLink></p>
      </form>
      <div class="quiet-note">本项目使用虚构校园与测试账号。管理员由本机初始化工具创建。<RouterLink to="/apply" class="text-link">商家申请入驻 →</RouterLink></div>
    </div>
  </section>
</template>
