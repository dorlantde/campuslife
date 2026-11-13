<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../context'
import type { MerchantShop, TimeInterval } from '../api/types'
import { copy, weekdays, weeklyBody } from './forms'
import { useLatestRead, useWrite } from './useRequests'
import IntervalEditor from './IntervalEditor.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
const props = defineProps<{ initial: MerchantShop }>()
const emit = defineEmits<{ saved: [shop: MerchantShop]; dirty: [value: boolean]; busy: [value: boolean] }>()
const base = ref(copy(props.initial))
function daysFrom(value: MerchantShop): Record<number, TimeInterval[]> { return Object.fromEntries(weekdays.map((_, i) => [i + 1, value.businessHours.filter(row => row.weekday === i + 1).map(row => ({ startTime: row.startTime, endTime: row.endTime }))])) }
const days = reactive(daysFrom(base.value))
const read = useLatestRead(), write = useWrite()
const dirty = computed(() => JSON.stringify(days) !== JSON.stringify(daysFrom(base.value)))
watch(dirty, value => emit('dirty', value), { immediate: true }); watch(write.busy, value => emit('busy', value))
function accept(value: MerchantShop) { base.value = copy(value); Object.assign(days, daysFrom(value)); emit('saved', value) }
function reload() { void read.run(signal => api<MerchantShop>('/api/v1/merchant/shop', { signal }), value => { accept(value); write.reset() }) }
async function save() { const value = await write.run(() => api<MerchantShop>('/api/v1/merchant/shop/business-hours', { method: 'PUT', body: weeklyBody(base.value.version, days) }), '整周营业时间已保存。'); if (value) accept(value) }
</script>
<template>
  <section class="surface panel management-form"><div class="section-title"><h2>每周营业时间</h2><el-button @click="reload" :disabled="write.busy.value" :loading="read.loading.value">重新载入（覆盖草稿）</el-button></div>
    <p class="muted">校园上海时间，保存会完整替换整周。未配置的星期休息，每天最多4段；相邻时段由系统合并，重叠会拒绝。整周清空也须点击保存。</p>
    <ErrorNotice :message="read.error.value || write.error.value" /><el-alert v-if="write.message.value" :title="write.message.value" type="success" :closable="false" class="notice" role="status" />
    <form @submit.prevent="save"><fieldset :disabled="write.busy.value || write.blocked.value || read.loading.value"><div v-for="(day, i) in weekdays" :key="day" class="day-row"><h3>{{ day }}</h3><IntervalEditor v-model="days[i + 1]" :prefix="day" :disabled="write.busy.value || write.blocked.value || read.loading.value" /></div><div class="form-actions"><el-button type="primary" native-type="submit" :loading="write.busy.value" :disabled="write.blocked.value">保存整周营业时间</el-button><el-button type="danger" text @click="Object.keys(days).forEach(key => days[Number(key)] = [])">清空整周草稿</el-button></div></fieldset></form>
    <p class="quiet-note">缩短营业时间须覆盖已有排班和受保护预约。发生保护冲突请先处理排班或订单；页面不会替你取消预约。</p>
  </section>
</template>
