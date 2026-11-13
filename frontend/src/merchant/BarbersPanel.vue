<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../context'
import type { Barber, MerchantService } from '../api/types'
import { copy } from './forms'
import { useLatestRead, useWrite } from './useRequests'
import ErrorNotice from '../components/ErrorNotice.vue'
import { askDiscard } from '../shared/discard'
const emit = defineEmits<{ dirty: [value: boolean]; busy: [value: boolean] }>()
const services = ref<MerchantService[]>([]), barbers = ref<Barber[]>([]), base = ref<Barber>()
const draft = reactive({ displayName: '', enabled: false, serviceIds: [] as string[] })
const read = useLatestRead(), write = useWrite()
const dirty = computed(() => draft.displayName !== (base.value?.displayName || '') || draft.enabled !== (base.value?.enabled || false) || [...draft.serviceIds].sort().join(',') !== [...(base.value?.serviceIds || [])].sort().join(','))
watch(dirty, value => emit('dirty', value), { immediate: true }); watch(write.busy, value => emit('busy', value))
async function choose(value?: Barber) {
  if (write.busy.value || (dirty.value && !await askDiscard('切换人员会覆盖未保存草稿。'))) return
  base.value = value ? copy(value) : undefined; Object.assign(draft, value ? { displayName: value.displayName, enabled: value.enabled, serviceIds: [...value.serviceIds] } : { displayName: '', enabled: false, serviceIds: [] }); write.reset()
}
async function load() { await read.run(async signal => { const [people, items] = await Promise.all([api<Barber[]>('/api/v1/merchant/barbers', { signal }), api<MerchantService[]>('/api/v1/merchant/services?serviceType=HAIRCUT', { signal })]); return { people, items } }, value => { barbers.value = value.people; services.value = value.items; base.value = undefined; Object.assign(draft, { displayName: '', enabled: false, serviceIds: [] }); write.reset() }) }
function remember(value: Barber) { barbers.value = [value, ...barbers.value.filter(b => b.id !== value.id)]; base.value = copy(value) }
async function saveProfile() {
  const original = base.value
  const result = await write.run(() => api<Barber>(original ? `/api/v1/merchant/barbers/${original.id}` : '/api/v1/merchant/barbers', { method: original ? 'PATCH' : 'POST', body: original ? { expectedVersion: original.version, displayName: draft.displayName, enabled: draft.enabled } : { displayName: draft.displayName, serviceIds: [...draft.serviceIds] } }), original ? '人员资料已保存；服务绑定须单独保存。' : '人员已创建，默认停用；请核对后启用。')
  if (result) { remember(result); draft.displayName = result.displayName; draft.enabled = result.enabled; if (!original) draft.serviceIds = [...result.serviceIds] }
}
async function saveBindings() {
  const original = base.value
  if (!original) return
  const result = await write.run(() => api<Barber>(`/api/v1/merchant/barbers/${original.id}/services`, { method: 'PUT', body: { expectedVersion: original.version, serviceIds: [...draft.serviceIds] } }), '完整服务绑定已保存；姓名和启停修改须单独保存。')
  if (result) { remember(result); draft.serviceIds = [...result.serviceIds] }
}
onMounted(load)
</script>
<template>
  <section class="surface panel management-form"><div class="section-title"><h2>理发师与服务绑定</h2><div class="form-actions"><el-button @click="load" :loading="read.loading.value" :disabled="write.busy.value">重新载入（覆盖草稿）</el-button><el-button @click="choose()" :disabled="write.busy.value || write.blocked.value">新增理发师</el-button></div></div>
    <p class="muted">理发师是店铺人员资料，不是登录账号。姓名、启停、服务绑定和所有日期排班共用人员版本。</p>
    <ErrorNotice :message="read.error.value || write.error.value" /><el-alert v-if="write.message.value" :title="write.message.value" type="success" :closable="false" class="notice" role="status" />
    <div class="table-scroll"><table class="management-table"><thead><tr><th>姓名</th><th>状态</th><th>绑定服务数</th><th>操作</th></tr></thead><tbody><tr v-for="b in barbers" :key="b.id"><td>{{ b.displayName }}<small>人员编号 {{ b.id }}</small></td><td>{{ b.enabled ? '已启用' : '已停用' }}</td><td>{{ b.serviceIds.length }}</td><td><el-button text @click="choose(b)" :disabled="write.busy.value || write.blocked.value" :aria-label="`编辑理发师${b.displayName}`">编辑</el-button></td></tr></tbody></table></div>
    <div class="editor-divider"><h3>{{ base ? `编辑人员：${base.displayName}` : '新增理发师' }}</h3><form @submit.prevent="saveProfile"><fieldset :disabled="write.busy.value || write.blocked.value || read.loading.value"><label for="barber-name">理发师姓名</label><el-input id="barber-name" v-model="draft.displayName" required /><label v-if="base" class="checkbox-line"><input v-model="draft.enabled" type="checkbox">启用此理发师</label>
      <div class="binding-editor"><h3>{{ base ? '完整服务绑定' : '初始服务绑定' }}</h3><p class="field-help">可选本店上架或下架理发服务，最多50项。保存空集合会解除全部绑定；未结束预约可能阻止解绑或停用。</p><label v-for="s in services" :key="s.id" class="checkbox-line"><input v-model="draft.serviceIds" type="checkbox" :value="s.id">{{ s.name }}{{ s.enabled ? '' : '（已下架）' }}</label><p v-if="!services.length" class="muted">暂无理发服务，可以先创建人员，再去服务目录配置。</p></div>
      <div class="form-actions"><el-button type="primary" native-type="submit" :loading="write.busy.value" :disabled="write.blocked.value">{{ base ? '保存姓名与启停' : '创建理发师' }}</el-button><el-button v-if="base" @click="saveBindings" :disabled="write.busy.value || write.blocked.value">保存完整服务绑定</el-button></div>
    </fieldset></form></div>
  </section>
</template>
