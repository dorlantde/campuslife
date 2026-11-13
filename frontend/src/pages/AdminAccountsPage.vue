<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api, session } from '../context'
import type { Account, Page, Role } from '../api/types'
import { useLatestRead, useWrite } from '../merchant/useRequests'
import { accountStatusBody } from '../admin/rules'
import { askDiscard } from '../shared/discard'
import { roleName } from '../shared/format'
import ErrorNotice from '../components/ErrorNotice.vue'
const read = useLatestRead(), write = useWrite(), result = ref<Page<Account>>(), role = ref<Role | ''>(''), status = ref<Account['status'] | ''>('')
const selected = ref<Account>(), nextStatus = ref<Account['status']>('DISABLED'), confirmed = ref(false), localError = ref('')
const actorId = session.current.value?.account.id || ''
async function load(page = 1) {
  if (write.busy.value) return
  if (selected.value && !await askDiscard('重新查询将清除当前选中的账号处理草稿，确认继续？')) return
  selected.value = undefined; confirmed.value = false; write.reset(); localError.value = ''
  const query = new URLSearchParams({ page: String(page), size: '20' }); if (role.value) query.set('role', role.value); if (status.value) query.set('status', status.value)
  await read.run(signal => api<Page<Account>>(`/api/v1/admin/accounts?${query}`, { signal }), value => { result.value = value })
}
async function choose(account: Account) {
  if (account.id === actorId || write.busy.value) return
  if (selected.value && !await askDiscard('切换处理账号会丢弃当前状态草稿，确认继续？')) return
  write.reset(); localError.value = ''; selected.value = { ...account }; nextStatus.value = account.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'; confirmed.value = false
}
async function save() {
  if (!selected.value || !confirmed.value || write.blocked.value) return
  try {
    localError.value = ''; const account = selected.value, body = accountStatusBody(account, actorId, nextStatus.value)
    const value = await write.run(() => api<Account>(`/api/v1/admin/accounts/${account.id}/status`, { method: 'PATCH', body }), '账号状态已更新；恢复后须重新登录，不自动退款存量订单。')
    if (value) { selected.value = undefined; confirmed.value = false; const message = write.message.value; await load(result.value?.page || 1); write.message.value = message }
  } catch (e) { localError.value = e instanceof Error ? e.message : '操作失败' }
}
onBeforeRouteLeave(async () => { if (write.busy.value) return false; if (selected.value) return askDiscard('账号状态草稿未提交，确认丢弃并离开？') })
onMounted(() => load())
</script>
<template><header class="page-heading"><span class="eyebrow">ADMIN ACCOUNTS</span><h1>账号状态管理</h1><p>查询平台业务账号。只能修改其他账号状态，不改角色或密码；停用会撤销旧会话，恢复须重新登录。</p></header><section class="surface panel admin-form"><div class="action-row"><label>账号角色<select v-model="role" aria-label="账号角色" :disabled="write.busy.value"><option value="">全部角色</option><option v-for="(label, key) in roleName" :key="key" :value="key">{{ label }}</option></select></label><label>账号状态<select v-model="status" aria-label="账号状态" :disabled="write.busy.value"><option value="">全部状态</option><option value="ACTIVE">正常</option><option value="DISABLED">停用</option></select></label><el-button :loading="read.loading.value" :disabled="write.busy.value" @click="load()">查询账号</el-button></div><ErrorNotice :message="localError || read.error.value || write.error.value" /><el-alert v-if="write.message.value" :title="write.message.value" type="success" :closable="false" role="status" /><p v-if="result">共{{ result.total }}个账号 · 第{{ result.page }}页</p><div class="table-scroll"><table class="management-table"><thead><tr><th>编号/用户名</th><th>昵称/角色</th><th>状态/版本</th><th></th></tr></thead><tbody><tr v-for="a in result?.items || []" :key="a.id"><td>{{ a.id }}<small>{{ a.username }}</small></td><td>{{ a.nickname }}<small>{{ roleName[a.role] }}</small></td><td>{{ a.status === 'ACTIVE' ? '正常' : '停用' }}<small>版本{{ a.version }}</small></td><td><el-button :disabled="a.id === actorId || write.busy.value || read.loading.value" @click="choose(a)">{{ a.id === actorId ? '本人不可修改' : '核对状态' }}</el-button></td></tr></tbody></table></div><el-pagination v-if="result && result.totalPages > 1" layout="prev, pager, next" :total="result.total" :page-size="result.size" :current-page="result.page" :disabled="write.busy.value || read.loading.value" @current-change="load" />
  <div v-if="selected" class="confirm-box"><h2>核对 {{ selected.username }} #{{ selected.id }}</h2><p>昵称{{ selected.nickname }} · 原状态{{ selected.status }} · 读取版本{{ selected.version }}</p><fieldset :disabled="write.busy.value || write.blocked.value"><label>目标状态<select v-model="nextStatus" aria-label="目标状态"><option value="ACTIVE">恢复正常</option><option value="DISABLED">停用</option></select></label><label class="acknowledgement"><input v-model="confirmed" type="checkbox" aria-label="确认账号状态影响">确认处理此账号；旧会话失效，存量订单不会自动取消或退款</label><el-button type="primary" :disabled="!confirmed" @click="save" :loading="write.busy.value">确认变更账号状态</el-button></fieldset><p class="field-help">旧版本冲突后请重新查询并核对，不自动取新版本重试。</p></div></section></template>
