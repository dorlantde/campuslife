<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../context'
import type { MerchantService } from '../api/types'
import { copy, serviceDraft, serviceBody } from './forms'
import { money } from '../shared/format'
import { useLatestRead, useWrite } from './useRequests'
import ErrorNotice from '../components/ErrorNotice.vue'
import { askDiscard } from '../shared/discard'
const props = defineProps<{ serviceType: 'HAIRCUT' | 'PRINT' }>()
const emit = defineEmits<{ dirty: [value: boolean]; busy: [value: boolean] }>()
const services = ref<MerchantService[]>([]), base = ref<MerchantService>()
const draft = reactive(serviceDraft())
const read = useLatestRead(), write = useWrite()
const dirty = computed(() => JSON.stringify(draft) !== JSON.stringify(serviceDraft(base.value)))
watch(dirty, value => emit('dirty', value), { immediate: true }); watch(write.busy, value => emit('busy', value))
async function choose(service?: MerchantService) {
  if (write.busy.value || (dirty.value && !await askDiscard('切换服务会覆盖未保存的草稿。'))) return
  base.value = service ? copy(service) : undefined; Object.assign(draft, serviceDraft(service)); write.reset()
}
function load() { void read.run(signal => api<MerchantService[]>('/api/v1/merchant/services', { signal }), value => { services.value = value; base.value = undefined; Object.assign(draft, serviceDraft()); write.reset() }) }
async function save() {
  const original = base.value
  const value = await write.run(() => api<MerchantService>(original ? `/api/v1/merchant/services/${original.id}` : '/api/v1/merchant/services', { method: original ? 'PATCH' : 'POST', body: serviceBody(props.serviceType, draft, original) }), original ? '服务已保存。' : '服务已创建；下架服务不会公开展示。')
  if (value) { services.value = [value, ...services.value.filter(s => s.id !== value.id)]; base.value = copy(value); Object.assign(draft, serviceDraft(value)) }
}
onMounted(load)
</script>
<template>
  <section class="surface panel management-form"><div class="section-title"><h2>服务目录</h2><div class="form-actions"><el-button @click="load" :disabled="write.busy.value" :loading="read.loading.value">重新载入（覆盖草稿）</el-button><el-button @click="choose()" :disabled="write.busy.value || write.blocked.value">新增服务</el-button></div></div>
    <ErrorNotice :message="read.error.value || write.error.value" /><el-alert v-if="write.message.value" :title="write.message.value" type="success" :closable="false" class="notice" role="status" />
    <div class="table-scroll"><table class="management-table"><thead><tr><th>服务名称</th><th>{{ serviceType === 'PRINT' ? '参考起价' : '固定价格' }}</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="service in services" :key="service.id"><td>{{ service.name }}<small>编号 {{ service.id }}</small></td><td>¥{{ money(service.priceAmount) }}<small v-if="service.durationMinutes !== null">{{ service.durationMinutes }} 分钟</small></td><td>{{ service.enabled ? '已上架' : '已下架' }}</td><td><el-button text :disabled="write.busy.value || write.blocked.value" :aria-label="`编辑服务${service.name}`" @click="choose(service)">编辑</el-button></td></tr></tbody></table></div>
    <el-empty v-if="!services.length && !read.loading.value && !read.error.value" description="还没有服务，请在下面填写并创建。" />
    <form @submit.prevent="save" class="editor-divider"><h3>{{ base ? `编辑服务：${base.name}` : '新增服务' }}</h3><fieldset :disabled="write.busy.value || write.blocked.value || read.loading.value"><div class="form-grid">
      <div><label for="service-name">服务名称</label><el-input id="service-name" v-model="draft.name" required /></div>
      <div><label for="service-price">{{ serviceType === 'PRINT' ? '参考起价（元）' : '服务价格（元）' }}</label><el-input id="service-price" v-model="draft.price" inputmode="decimal" placeholder="例如 25.00" required /></div>
      <div v-if="serviceType === 'HAIRCUT'"><label for="service-duration">服务时长（分钟）</label><el-input id="service-duration" v-model="draft.duration" inputmode="numeric" placeholder="15～240，15的倍数" required /></div>
      <div class="span-two"><label for="service-description">服务介绍</label><el-input id="service-description" v-model="draft.description" type="textarea" :rows="3" /></div>
      <label class="checkbox-line span-two"><input v-model="draft.enabled" type="checkbox">上架此服务</label>
    </div><p class="field-help">{{ serviceType === 'PRINT' ? '打印实际金额由规格价目报价；参考起价不会替代价目。' : '服务价格和时长变更只影响新订单。' }} 服务版本与店铺版本独立，上架不等于有空位或可以下单。</p><el-button type="primary" native-type="submit" :loading="write.busy.value" :disabled="write.blocked.value">{{ base ? '保存服务修改' : '创建服务' }}</el-button></fieldset></form>
    <p v-if="write.blocked.value" class="quiet-note">创建结果不确定时，先重新载入并在列表核对，不能自动重复创建同名服务。</p>
  </section>
</template>
