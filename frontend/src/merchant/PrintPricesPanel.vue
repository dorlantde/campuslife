<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../context'
import type { MerchantService, PrintPrice } from '../api/types'
import { priceBody, priceDrafts, type PriceDraft } from './forms'
import { useLatestRead, useWrite } from './useRequests'
import ErrorNotice from '../components/ErrorNotice.vue'
import { askDiscard } from '../shared/discard'
const emit = defineEmits<{ dirty: [value: boolean]; busy: [value: boolean] }>()
const services = ref<MerchantService[]>([]), selectedId = ref(''), loadedId = ref('')
const rows = ref<PriceDraft[]>([]), initial = ref('')
const read = useLatestRead(), write = useWrite()
const ready = computed(() => !!loadedId.value && selectedId.value === loadedId.value)
const dirty = computed(() => !!loadedId.value && JSON.stringify(rows.value) !== initial.value)
watch(dirty, value => emit('dirty', value), { immediate: true }); watch(write.busy, value => emit('busy', value))
function accept(value: PrintPrice[]) { rows.value = priceDrafts(value); initial.value = JSON.stringify(rows.value) }
async function load() {
  if (!selectedId.value || (dirty.value && !await askDiscard('读取价目会覆盖当前未保存草稿。'))) return
  const id = selectedId.value
  await read.run(signal => api<PrintPrice[]>(`/api/v1/merchant/services/${id}/print-prices`, { signal }), value => { loadedId.value = id; accept(value); write.reset() })
}
async function initialize() {
  await read.run(signal => api<MerchantService[]>('/api/v1/merchant/services?serviceType=PRINT', { signal }), value => { services.value = value; if (!selectedId.value) selectedId.value = value[0]?.id || '' })
  if (selectedId.value) await load()
}
async function save() {
  if (!ready.value) return
  const id = loadedId.value
  const value = await write.run(() => api<PrintPrice[]>(`/api/v1/merchant/services/${id}/print-prices`, { method: 'PUT', body: priceBody(rows.value) }), '本次勾选的价目已保存，其余规格保留。')
  if (value) accept(value)
}
onMounted(initialize)
</script>
<template>
  <section class="surface panel management-form"><h2>打印规格价目</h2><p class="muted">A4黑白/彩色 × 单面/双面。每条价目有独立版本，单价按印刷面计算；双面不会自动半价。</p>
    <div class="resource-selector"><div><label for="price-service">打印服务</label><select id="price-service" v-model="selectedId" :disabled="read.loading.value || write.busy.value"><option value="" disabled>请选择服务</option><option v-for="service in services" :key="service.id" :value="service.id">{{ service.name }}{{ service.enabled ? '' : '（已下架）' }}</option></select></div><el-button @click="services.length ? load() : initialize()" :disabled="write.busy.value" :loading="read.loading.value">读取价目（覆盖草稿）</el-button></div>
    <ErrorNotice :message="read.error.value || write.error.value" /><el-alert v-if="write.message.value" :title="write.message.value" type="success" :closable="false" class="notice" role="status" />
    <el-empty v-if="!services.length && !read.loading.value && !read.error.value" description="请先到服务目录创建打印服务；下架服务也可设置价目。" />
    <p v-if="loadedId && !ready" class="inline-error">服务选择已变化，请读取所选服务后再保存。</p>
    <form v-if="loadedId" @submit.prevent="save"><fieldset :disabled="!ready || read.loading.value || write.busy.value || write.blocked.value"><div class="table-scroll"><table class="management-table"><thead><tr><th>本次保存</th><th>规格</th><th>单价（元/印刷面）</th><th>启用</th></tr></thead><tbody><tr v-for="row in rows" :key="`${row.colorMode}-${row.sideMode}`"><td><input v-model="row.include" type="checkbox" :aria-label="`保存${row.colorMode === 'BW' ? '黑白' : '彩色'}${row.sideMode === 'SINGLE' ? '单面' : '双面'}`"></td><td>A4 {{ row.colorMode === 'BW' ? '黑白' : '彩色' }} / {{ row.sideMode === 'SINGLE' ? '单面' : '双面' }}<small>{{ row.expectedVersion === null ? '尚未配置' : '已配置' }}</small></td><td><el-input v-model="row.price" inputmode="decimal" :aria-label="`${row.colorMode === 'BW' ? '黑白' : '彩色'}${row.sideMode === 'SINGLE' ? '单面' : '双面'}单价`" :required="row.include" :disabled="!row.include" placeholder="0.01～1000.00" /></td><td><input v-model="row.enabled" type="checkbox" :disabled="!row.include" :aria-label="`启用${row.colorMode === 'BW' ? '黑白' : '彩色'}${row.sideMode === 'SINGLE' ? '单面' : '双面'}`"></td></tr></tbody></table></div><el-button type="primary" native-type="submit" :disabled="!ready || write.blocked.value" :loading="write.busy.value">保存勾选规格</el-button></fieldset></form>
    <p class="quiet-note">未勾选的规格保留，不表示删除或停用。要停用已有规格，勾选“本次保存”并取消其“启用”。任一项冲突整批失败，重新读取后再核对，不会自动换版本重试。</p>
  </section>
</template>
