<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { RouterLink, useRoute, onBeforeRouteLeave } from 'vue-router'
import { api, session } from '../context'
import type { OrderDetail, OrderEvent, OwnReview } from '../api/types'
import type { PdfDownload } from '../api/client'
import { errorText } from '../api/client'
import { savePdf } from '../api/files'
import { campusTime, money, roleName } from '../shared/format'
import { askDiscard } from '../shared/discard'
import { actionNames, merchantCanDownload, paymentNames, printActions, bookingActions, canWriteReview, progressNames, statusNames } from '../orders/rules'
import ErrorNotice from '../components/ErrorNotice.vue'
const props = defineProps<{ merchant?: boolean }>(), route = useRoute()
const id = String(route.params.orderId), list = props.merchant ? '/merchant/orders' : '/orders', base = `/api/v1${list}/${id}`
const order = ref<OrderDetail>(), events = ref<OrderEvent[]>([]), ownReview = ref<OwnReview>()
const error = ref(''), readError = ref(''), message = ref(''), fresh = ref(false), loading = ref(false), busy = ref(false), downloading = ref(false)
const chosen = ref(''), reason = ref(''), rating = ref('5'), content = ref(''), reviewError = ref('')
let live = true, sequence = 0
const epoch = session.generation.value, current = () => live && epoch === session.generation.value
onScopeDispose(() => { live = false; sequence++ })
const labels: Record<string, string> = { 'payments/mock': '模拟支付', cancel: props.merchant ? '无法履约取消并退款' : '取消订单', pickup: '确认已取件', accept: '接单', reject: '拒单并退款', start: '开始服务', complete: '完成服务', 'ready-for-pickup': '标记待取件' }
const actionLabel = (action: string) => action === 'start' && order.value?.orderType === 'PRINT' ? '开始打印' : labels[action]
const clock = ref(Date.now())
const confirmLabel = computed(() => chosen.value === 'pickup' ? '确认取件' : `确认${actionLabel(chosen.value) || ''}`)
const available = computed(() => order.value ? order.value.orderType === 'PRINT' ? printActions(order.value, !!props.merchant) : bookingActions(order.value, !!props.merchant, clock.value) : [])
const canReview = computed(() => !props.merchant && !!order.value && canWriteReview(order.value))
const canDownload = computed(() => !!order.value?.printing && (!props.merchant || merchantCanDownload(order.value)))
async function load() {
  const seq = ++sequence; loading.value = true; fresh.value = false; readError.value = ''
  try {
    const [detail, timeline] = await Promise.all([api<OrderDetail>(base), api<OrderEvent[]>(`${base}/events`)])
    if (current() && seq === sequence) { order.value = detail; events.value = timeline; fresh.value = true; clock.value = Date.now() }
  } catch (e) { if (current() && seq === sequence) readError.value = errorText(e) }
  finally { if (current() && seq === sequence) loading.value = false }
}
async function reload() { if (busy.value) return; chosen.value = ''; error.value = ''; message.value = ''; await load(); if (fresh.value && order.value?.reviewed && !props.merchant) await readReview() }
async function selectAction(action: string) { if (!busy.value) { chosen.value = action; reason.value = ''; error.value = ''; message.value = '' } }
async function act() {
  if (busy.value || !fresh.value || !available.value.includes(chosen.value)) return
  const action = chosen.value, needsReason = props.merchant && ['reject', 'cancel'].includes(action)
  if (needsReason && (![...reason.value.trim()].length || [...reason.value.trim()].length > 300)) { error.value = '请填写1～300个字符的原因。'; return }
  busy.value = true; error.value = ''; message.value = ''
  try { await api(`${base}/${action}`, { method: 'POST', ...(needsReason ? { body: { reason: reason.value } } : {}) }); if (current()) { message.value = `${actionLabel(action)}已提交，以下为重新读取的订单事实。`; chosen.value = ''; reason.value = '' } }
  catch (e) { if (current()) error.value = `${errorText(e)} 已尝试重新读取核对；截止时间冲突可能已先关闭或退款。` }
  finally { if (current()) { await load(); busy.value = false } }
}
async function download() {
  if (!order.value?.printing || downloading.value) return
  downloading.value = true; error.value = ''; message.value = ''
  try { const pdf = await api<PdfDownload>(`/api/v1/files/${order.value.printing.fileId}/content`, { response: 'pdf' }); if (current()) { savePdf(pdf.blob, pdf.filename); message.value = 'PDF已交给浏览器保存，请查看下载结果。' } }
  catch (e) { if (current()) error.value = errorText(e) }
  finally { if (current()) downloading.value = false }
}
async function readReview() {
  reviewError.value = ''
  try { const value = await api<OwnReview>(`${base}/review`); if (current()) ownReview.value = value }
  catch (e) { if (current()) reviewError.value = errorText(e) }
}
async function review() {
  if (busy.value || !fresh.value || !canReview.value) return
  if (!/^[1-5]$/.test(rating.value) || ![...content.value.trim()].length || [...content.value.trim()].length > 1000) { reviewError.value = '评分为1～5整数，文字为1～1000个字符。'; return }
  busy.value = true; reviewError.value = ''; message.value = ''
  try { const value = await api<OwnReview>(`${base}/review`, { method: 'POST', body: { rating: Number(rating.value), content: content.value } }); if (current()) { ownReview.value = value; content.value = ''; message.value = '评价已保存，一单一次。' } }
  catch (e) { if (current()) reviewError.value = errorText(e) }
  finally { if (current()) { await load(); if (order.value?.reviewed) await readReview(); busy.value = false } }
}
onBeforeRouteLeave(async () => { if (busy.value) return false; if (content.value || reason.value) return askDiscard('评价或处理原因尚未提交，确认丢弃草稿并离开？') })
onMounted(reload)
</script>
<template>
  <RouterLink :to="list" class="back-link">← {{ merchant ? '本店订单任务' : '我的订单' }}</RouterLink><header class="page-heading"><span class="eyebrow">{{ merchant ? 'MERCHANT ORDER' : 'ORDER DETAILS' }}</span><h1>订单详情 <small>#{{ id }}</small></h1><p>金额和名称以下单快照为准；状态与资金以本次读取结果为准。</p></header>
  <ErrorNotice :message="error" /><ErrorNotice :message="readError" /><el-alert v-if="message" :title="message" type="success" :closable="false" role="status" /><div class="action-row"><el-button :loading="loading" :disabled="busy" @click="reload">刷新订单与事件</el-button><span class="field-help">时间均为上海校园时间；页面不自动关单或退款。</span></div>
  <template v-if="order"><section class="surface panel"><div class="order-top"><div><span class="status open">{{ statusNames[order.status] }}</span><h2>{{ order.serviceName }}</h2><p>{{ order.shopName }} · {{ order.orderNo }}</p></div><div class="quote-total">¥{{ money(order.payableAmount) }}<small>{{ paymentNames[order.paymentStatus] }}</small></div></div>
    <div class="detail-facts"><p>原价 ¥{{ money(order.originalAmount) }} · 优惠 ¥{{ money(order.discountAmount) }} · 版本 {{ order.version }}</p><p>创建 {{ campusTime(order.createdAt) }} · 支付截止 {{ campusTime(order.paymentDeadline) }}</p><p v-if="order.acceptanceDeadline">接单截止 {{ campusTime(order.acceptanceDeadline) }}（到期只作提示，以实际状态为准）</p><p v-if="order.paidAt">已付 {{ campusTime(order.paidAt) }}</p><p v-if="order.acceptedAt">已接单 {{ campusTime(order.acceptedAt) }}</p><p v-if="order.startedAt">已开始 {{ campusTime(order.startedAt) }}</p><p v-if="order.completedAt">已完成 {{ campusTime(order.completedAt) }}</p><p v-if="order.note">订单备注：{{ order.note }}</p></div>
    <div v-if="order.booking" class="file-card"><h3>理发预约 · {{ order.booking.barberName }}</h3><p>{{ campusTime(order.booking.startAt) }} — {{ campusTime(order.booking.endAt) }}（上海）· {{ order.booking.durationMinutes }}分钟</p><p>占用状态 {{ order.booking.occupancyStatus }}</p><p class="field-help">学生已接单取消须至少提前1小时（等于允许）；商家开始仅预约区间内，按钮以本机时间/最近读取提示，最终由系统校验。到时请手动刷新；完成仍保留原预约区间。</p></div><div v-if="order.printing" class="file-card"><h3>{{ progressNames[order.printing.progress] }}</h3><strong>{{ order.printing.fileName }}</strong><p>文件ID {{ order.printing.fileId }} · {{ order.printing.pageCount }}页 × {{ order.printing.copies }}份 · {{ order.printing.sheetCount }}张纸</p><p>A4 · {{ order.printing.colorMode === 'BW' ? '黑白' : '彩色' }} · {{ order.printing.sideMode === 'SINGLE' ? '单面' : '双面' }} · ¥{{ money(order.printing.unitPriceAmount) }}/印刷面</p><p v-if="order.printing.readyAt">待取件 {{ campusTime(order.printing.readyAt) }}</p><p v-if="order.printing.pickedUpAt">已取件 {{ campusTime(order.printing.pickedUpAt) }}</p><el-button v-if="canDownload" :loading="downloading" @click="download">{{ merchant ? '下载任务PDF' : '下载本人PDF' }}</el-button><p v-else-if="merchant" class="field-help">当前订单阶段不授予商家下载权；其他合格任务仍可能授权同一文件。</p></div>
    <div class="fund-facts"><p v-if="order.payment">模拟支付 {{ order.payment.paymentNo }} · ¥{{ money(order.payment.amount) }} · {{ campusTime(order.payment.paidAt) }}</p><p v-if="order.refund">模拟退款 {{ order.refund.refundNo }} · ¥{{ money(order.refund.amount) }} · {{ campusTime(order.refund.refundedAt) }}</p><p v-if="order.closedAt">关闭 {{ campusTime(order.closedAt) }} · {{ order.closeReason }}</p><p v-if="order.closeNote" class="pre-wrap">原因：{{ order.closeNote }}</p></div>
    <div class="action-row"><el-button v-for="action in available" :key="action" :disabled="busy || !fresh" @click="selectAction(action)">{{ actionLabel(action) }}</el-button></div><div v-if="chosen && available.includes(chosen)" class="confirm-box"><h3>{{ confirmLabel }}？</h3><p v-if="chosen === 'payments/mock'">将模拟支付冻结金额 ¥{{ money(order.payableAmount) }}，不涉及真实资金。</p><p v-if="chosen === 'pickup'">请确认已实际领取打印资料。确认后订单完成。</p><p v-if="chosen === 'start'">开始后不能普通取消；确认实际到店或打印任务后再开始。预约仅原区间内允许开始。</p><label v-if="merchant && ['reject', 'cancel'].includes(chosen)">处理原因<textarea v-model="reason" aria-label="处理原因" rows="3" placeholder="1～300个字符"></textarea></label><div class="action-row"><el-button type="primary" :disabled="busy || !fresh" :loading="busy" @click="act">{{ confirmLabel }}</el-button><el-button :disabled="busy" @click="chosen = ''; reason = ''">暂不操作</el-button></div></div>
  </section>
  <section class="surface panel"><h2>订单时间线</h2><ol class="order-timeline"><li v-for="event in events" :key="event.id"><strong>{{ actionNames[event.action] || event.action }}</strong><span>{{ event.fromStatus ? statusNames[event.fromStatus] : '—' }} → {{ statusNames[event.toStatus] }}</span><time>{{ campusTime(event.occurredAt) }}</time><small>序号{{ event.eventSeq }} · {{ event.actorType === 'SYSTEM' ? '系统' : roleName[event.actorType as keyof typeof roleName] || event.actorType }}{{ event.actorId ? ` #${event.actorId}` : '' }}</small><p v-if="event.reason" class="pre-wrap">{{ event.reason }}</p></li></ol><p v-if="!events.length">暂无事件记录。</p></section>
  <section v-if="!merchant && (canReview || order.reviewed)" class="surface panel"><h2>本人订单评价</h2><ErrorNotice :message="reviewError" /><div v-if="ownReview"><strong>{{ ownReview.rating }} / 5 · {{ ownReview.visibility === 'HIDDEN' ? '已隐藏（本人仍可查看）' : '公开可见' }}</strong><p class="pre-wrap">{{ ownReview.content }}</p><small>评价ID {{ ownReview.id }} · {{ campusTime(ownReview.createdAt) }}</small></div><el-button v-else-if="order.reviewed" @click="readReview">读取本人评价</el-button><form v-else @submit.prevent="review"><label>评价评分<select v-model="rating" aria-label="评价评分"><option v-for="n in 5" :value="String(n)" :key="n">{{ n }}分</option></select></label><label>评价内容<textarea v-model="content" aria-label="评价内容" rows="4" placeholder="1～1000个字符，一单一次，不可修改"></textarea></label><el-button type="primary" native-type="submit" :disabled="busy || !fresh" :loading="busy">提交订单评价</el-button></form></section>
  </template>
</template>
