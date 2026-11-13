<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { Account } from '../api/types'
import { api, session } from '../context'
import { router } from '../router'
import { ApiError, errorText } from '../api/client'
import { roleName } from '../shared/format'
import ErrorNotice from '../components/ErrorNotice.vue'
import { hasUnconfirmedCreate } from '../orders/pending'
import { askDiscard } from '../shared/discard'
const account = ref<Account>()
const nickname = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
async function refresh(preserveDraft = false) {
  loading.value = true
  const epoch = session.generation.value
  try { const a = await api<Account>('/api/v1/me'); account.value = a; session.update(a, epoch); if (!preserveDraft) nickname.value = a.nickname }
  finally { loading.value = false }
}
async function load() { error.value = ''; try { await refresh() } catch(e) { error.value = errorText(e) } }
async function saveNickname() {
  if (busy.value || !account.value) return
  busy.value = true; error.value = ''; message.value = ''
  const epoch = session.generation.value
  try {
    const a = await api<Account>('/api/v1/me', { method: 'PATCH', body: { nickname: nickname.value, expectedVersion: account.value.version } })
    session.update(a, epoch); account.value = a; nickname.value = a.nickname; message.value = '昵称已保存。'
  } catch(e) {
    error.value = errorText(e)
    if (e instanceof ApiError && (e.code === 'CONFIG_VERSION_CONFLICT' || e.code === 'REQUEST_TIMEOUT' || e.code === 'NETWORK_ERROR')) {
      try { await refresh(true); error.value += ' 已读取当前资料，请核对下面的当前昵称，再决定是否提交草稿。' } catch(next) { error.value += ` ${errorText(next)}` }
    }
  } finally { busy.value = false }
}
async function changePassword() {
  if (busy.value) return
  if (newPassword.value !== confirmPassword.value) { error.value = '两次新密码不一致。'; return }
  if (session.current.value && hasUnconfirmedCreate(session.current.value.account.id) && !await askDiscard('改密成功会退出并清除未确认的下单恢复记录。请先核对订单；确认丢弃恢复记录后再继续改密。')) return
  busy.value = true; error.value = ''; message.value = ''
  const epoch = session.generation.value
  try {
    await api('/api/v1/me/password', { method: 'POST', body: { currentPassword: currentPassword.value, newPassword: newPassword.value } })
    if (epoch === session.generation.value) { session.clear('密码已修改，所有旧会话已撤销，请重新登录。'); await router.replace('/login') }
  } catch(e) { error.value = errorText(e) }
  finally { currentPassword.value = ''; newPassword.value = ''; confirmPassword.value = ''; busy.value = false }
}
onMounted(load)
</script>
<template>
  <div class="page-heading"><span class="eyebrow">MY ACCOUNT</span><h1>本人资料</h1><p class="muted">管理你的展示昵称与账号密码。</p></div>
  <ErrorNotice :message="error" /><el-alert v-if="message" :title="message" type="success" :closable="false" class="notice" role="status" />
  <div class="profile-grid">
    <section class="surface panel"><h2>基本资料</h2><el-button text @click="load" :loading="loading" :disabled="busy">重新读取资料</el-button>
      <template v-if="account"><dl class="info-list"><dt>账号</dt><dd>{{ account.username }}</dd><dt>身份</dt><dd>{{ roleName[account.role] }}</dd><dt>当前昵称</dt><dd>{{ account.nickname }}</dd></dl>
        <form class="stack-form" @submit.prevent="saveNickname"><label for="edit-nickname">修改昵称</label><el-input id="edit-nickname" v-model="nickname" :disabled="busy || loading" required /><p class="field-help">去除首尾空白后 1～40 个字符。若资料已变化，会要求重新核对。</p><el-button type="primary" native-type="submit" :loading="busy" :disabled="loading">保存昵称</el-button></form>
      </template><el-skeleton v-else-if="loading" :rows="4" animated />
    </section>
    <section class="surface panel"><h2>修改密码</h2><p class="muted">修改成功后，所有设备都需要重新登录。</p><form class="stack-form" @submit.prevent="changePassword">
      <label for="current-password">当前密码</label><el-input id="current-password" v-model="currentPassword" type="password" autocomplete="current-password" show-password :disabled="busy" required />
      <label for="new-password">新密码</label><el-input id="new-password" v-model="newPassword" type="password" autocomplete="new-password" show-password :disabled="busy" required />
      <label for="confirm-password">再次输入新密码</label><el-input id="confirm-password" v-model="confirmPassword" type="password" autocomplete="new-password" :disabled="busy" required />
      <p class="field-help">至少 8 个字符，UTF-8 不超过 72 字节；请保管好新密码。</p><el-button native-type="submit" :loading="busy">确认修改密码</el-button>
    </form></section>
  </div>
</template>
