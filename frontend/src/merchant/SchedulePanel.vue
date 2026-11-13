<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../context'
import type { Barber, Schedule, TimeInterval } from '../api/types'
import { campusDates, checkIntervals, copy } from './forms'
import { useLatestRead, useWrite } from './useRequests'
import IntervalEditor from './IntervalEditor.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import { askDiscard } from '../shared/discard'
const emit = defineEmits<{ dirty: [value: boolean]; busy: [value: boolean] }>()
const barbers = ref<Barber[]>([]), selectedId = ref(''), selectedDate = ref(campusDates()[0]!)
const loaded = ref<{ barberId: string; data: Schedule }>()
const intervals = ref<TimeInterval[]>([])
const read = useLatestRead(), write = useWrite()
const ready = computed(() => !!loaded.value && loaded.value.barberId === selectedId.value && loaded.value.data.date === selectedDate.value)
const writable = computed(() => ready.value && campusDates().includes(selectedDate.value))
const dirty = computed(() => !!loaded.value && JSON.stringify(intervals.value) !== JSON.stringify(loaded.value.data.intervals))
watch(dirty, value => emit('dirty', value), { immediate: true }); watch(write.busy, value => emit('busy', value))
async function load() {
  if (!selectedId.value || !selectedDate.value || (dirty.value && !await askDiscard('读取排班将覆盖当前未保存的时段。'))) return
  const id = selectedId.value, date = selectedDate.value
  await read.run(signal => api<Schedule>(`/api/v1/merchant/barbers/${id}/schedule?date=${encodeURIComponent(date)}`, { signal }), value => { loaded.value = { barberId: id, data: copy(value) }; intervals.value = copy(value.intervals); write.reset() })
}
async function initialize() {
  await read.run(signal => api<Barber[]>('/api/v1/merchant/barbers', { signal }), value => { barbers.value = value; if (!selectedId.value) selectedId.value = value[0]?.id || '' })
  if (selectedId.value) await load()
}
async function save() {
  const snapshot = loaded.value
  if (!snapshot || !writable.value) return
  const value = await write.run(() => api<Schedule>(`/api/v1/merchant/barbers/${snapshot.barberId}/schedule`, { method: 'PUT', body: { date: snapshot.data.date, expectedBarberVersion: snapshot.data.barberVersion, intervals: checkIntervals(intervals.value, true) } }), '当日排班已完整保存。')
  if (value) { loaded.value = { barberId: snapshot.barberId, data: copy(value) }; intervals.value = copy(value.intervals) }
}
onMounted(initialize)
</script>
<template>
  <section class="surface panel management-form"><h2>理发师按日排班</h2><p class="muted">校园上海时间；可写{{ campusDates()[0] }}至{{ campusDates()[6] }}，其它日期仅查询。保存完整替换该日，空数组清空；每段须15分钟对齐并位于营业时间内。</p>
    <div class="resource-selector"><div><label for="schedule-barber">排班理发师</label><select id="schedule-barber" v-model="selectedId" :disabled="read.loading.value || write.busy.value"><option value="" disabled>请选择理发师</option><option v-for="b in barbers" :key="b.id" :value="b.id">{{ b.displayName }}{{ b.enabled ? '' : '（已停用）' }}</option></select></div><div><label for="schedule-date">校园排班日期</label><input id="schedule-date" v-model="selectedDate" type="date" min="1000-01-01" max="9999-12-31" :disabled="read.loading.value || write.busy.value"></div><el-button @click="barbers.length ? load() : initialize()" :disabled="write.busy.value" :loading="read.loading.value">读取排班（覆盖草稿）</el-button></div>
    <ErrorNotice :message="read.error.value || write.error.value" /><el-alert v-if="write.message.value" :title="write.message.value" type="success" :closable="false" class="notice" role="status" />
    <el-empty v-if="!barbers.length && !read.loading.value && !read.error.value" description="请先创建理发师。" />
    <p v-if="loaded && !ready" class="inline-error">人员或日期选择已变化，必须读取目标排班后再保存，当前草稿不会写入新目标。</p>
    <form v-if="loaded" @submit.prevent="save"><fieldset :disabled="!writable || read.loading.value || write.busy.value || write.blocked.value"><h3>已读取：{{ loaded.data.date }}</h3><IntervalEditor v-model="intervals" prefix="当日排班" aligned :disabled="!writable || read.loading.value || write.busy.value || write.blocked.value" /><el-button type="primary" native-type="submit" :disabled="!writable || write.blocked.value" :loading="write.busy.value">保存当日完整排班</el-button></fieldset></form>
    <p class="quiet-note">读取返回的人员版本用于本次保存。另一页修改姓名、启停、绑定或其他日期后，可能发生版本冲突；需重新读取核对，不自动覆盖。</p>
  </section>
</template>
