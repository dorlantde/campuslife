<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { api } from '../context'
import type { OrderDetail } from '../api/types'
import { exceptionBody } from '../admin/rules'
import { positiveId, statusNames, paymentNames, progressNames } from '../orders/rules'
import { useLatestRead, useWrite } from '../merchant/useRequests'
import { campusTime, money } from '../shared/format'
import { askDiscard } from '../shared/discard'
import ErrorNotice from '../components/ErrorNotice.vue'
const route = useRoute(), input = ref(String(route.params.orderId || '')), order = ref<OrderDetail>(), reason = ref(''), verified = ref(false)
const read = useLatestRead(), write = useWrite(), localError = ref('')
const sameTarget = computed(() => !!order.value && order.value.id === input.value.trim())
const eligible = computed(() => sameTarget.value && order.value?.paymentStatus === 'PAID' && ['PENDING_ACCEPTANCE', 'ACCEPTED', 'IN_SERVICE'].includes(order.value.status))
async function load() {
  if (write.busy.value) return
  if (reason.value && !await askDiscard('重新读取将覆盖核实对象/版本并清除原因，需要重新核实，确认继续？')) return
  localError.value = ''; order.value = undefined; reason.value = ''; verified.value = false; write.reset()
  try { const id = positiveId(input.value.trim(), '订单'); await read.run(signal => api<OrderDetail>(`/api/v1/admin/orders/${id}`, { signal }), value => { order.value = value; input.value = value.id }) }
  catch (e) { localError.value = e instanceof Error ? e.message : '订单编号无效' }
}
async function close() {
  if (!order.value || !eligible.value || !verified.value || write.blocked.value) return
  try { localError.value = ''; const body = exceptionBody(order.value, reason.value); const value = await write.run(() => api<OrderDetail>(`/api/v1/admin/orders/${order.value!.id}/close-exception`, { method: 'POST', body }), '核实关闭已提交，以下为服务端订单事实。'); if (value) { order.value = value; reason.value = ''; verified.value = false } }
  catch (e) { localError.value = e instanceof Error ? e.message : '核实失败' }
}
onBeforeRouteLeave(async () => { if (write.busy.value) return false; if (reason.value) return askDiscard('核实原因未提交，确认丢弃并离开？') })
onMounted(() => { if (input.value) void load() })
</script>
<template><header class="page-heading"><span class="eyebrow">ADMIN EXCEPTION REVIEW</span><h1>订单异常核实</h1><p>输入双方提供的订单编号读取真实快照，再核实原因及版本。没有全平台订单列表接口。</p></header><section class="surface panel admin-form"><div class="action-row"><label>订单编号<input v-model="input" aria-label="订单编号" inputmode="numeric" :disabled="write.busy.value"></label><el-button :disabled="write.busy.value" :loading="read.loading.value" @click="load">读取订单核实快照</el-button></div><ErrorNotice :message="localError || read.error.value || write.error.value" /><el-alert v-if="write.message.value" :title="write.message.value" type="success" :closable="false" role="status" />
  <template v-if="order"><h2>{{ order.shopName }} · {{ order.serviceName }}</h2><p>{{ order.orderType === 'PRINT' ? '打印' : '预约' }} #{{ order.id }} · {{ statusNames[order.status] }} / {{ paymentNames[order.paymentStatus] }} · 读取版本{{ order.version }}</p><p>冻结金额 ¥{{ money(order.payableAmount) }} · 创建 {{ campusTime(order.createdAt) }}（上海）</p><div v-if="order.booking" class="file-card"><strong>{{ order.booking.barberName }}</strong><p>{{ campusTime(order.booking.startAt) }} — {{ campusTime(order.booking.endAt) }} · {{ order.booking.durationMinutes }}分钟 · {{ order.booking.occupancyStatus }}</p></div><div v-if="order.printing" class="file-card"><strong>{{ order.printing.fileName }}</strong><p>{{ order.printing.pageCount }}页 × {{ order.printing.copies }}份 · {{ progressNames[order.printing.progress] }} · 文件ID{{ order.printing.fileId }}</p><p>管理员无PDF内容下载权限；这里仅为订单冻结元数据。</p></div><p v-if="order.paidAt">付款 {{ campusTime(order.paidAt) }}</p><p v-if="order.acceptedAt">接单 {{ campusTime(order.acceptedAt) }}</p><p v-if="order.startedAt">开始 {{ campusTime(order.startedAt) }}</p><p v-if="order.completedAt">完成 {{ campusTime(order.completedAt) }}</p><p v-if="order.refund">退款 {{ order.refund.refundNo }} · ¥{{ money(order.refund.amount) }} · {{ campusTime(order.refund.refundedAt) }}</p><p v-if="order.closeReason">关闭 {{ order.closeReason }} · {{ order.closeNote }}</p><p v-if="!sameTarget" class="inline-error">输入目标已变化，请重新读取后核实。</p><p v-if="!eligible" class="field-help">只有已付的待接单/已接单/服务中订单可核实关闭，已完成不可退款。</p><fieldset :disabled="!eligible || write.busy.value || write.blocked.value || read.loading.value"><label>核实原因<textarea v-model="reason" aria-label="核实原因" rows="3" placeholder="1～300个字符"></textarea></label><label class="acknowledgement"><input v-model="verified" type="checkbox" aria-label="已核实双方并确认全额模拟退款">已核实双方及当前订单版本，确认全额模拟退款并关闭</label><el-button type="primary" :disabled="!verified" :loading="write.busy.value" @click="close">确认核实退款关闭</el-button></fieldset><p class="quiet-note">冲突或网络结果不确定时不会自动换版本重试，请明确重新读取并重新核实。目标账号停用不妨碍合法旧单异常处理。</p></template></section></template>
