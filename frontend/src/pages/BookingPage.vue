<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { onBeforeRouteLeave, RouterLink, useRoute } from 'vue-router'
import { api, session } from '../context'
import type { BookingSlots, OrderDetail, PublicBarber, ShopDetail, ShopService } from '../api/types'
import { ApiError, errorText } from '../api/client'
import { pendingBooking, type PendingBooking } from '../orders/pending'
import { bookingBody } from '../orders/rules'
import { campusDates } from '../merchant/forms'
import { campusTime, money } from '../shared/format'
import { askDiscard } from '../shared/discard'
import { useLatestRead } from '../merchant/useRequests'
import ErrorNotice from '../components/ErrorNotice.vue'
const route = useRoute(), shopId = String(route.params.shopId), serviceId = String(route.params.serviceId)
const accountId = session.current.value?.account.id || '', epoch = session.generation.value
const read = useLatestRead(), shop = ref<ShopDetail>(), service = ref<ShopService>(), barbers = ref<PublicBarber[]>([])
const dates = campusDates(), date = ref(dates[0]!), barberId = ref(''), selected = ref(''), note = ref('')
const slots = ref<BookingSlots>(), previous = ref<BookingSlots>(), loadedTarget = ref(''), error = ref(''), message = ref(''), busy = ref(false)
const pending = ref<PendingBooking | null>(pendingBooking.read(accountId))
let live = true
onScopeDispose(() => { live = false })
const current = () => live && !!accountId && epoch === session.generation.value && session.current.value?.account.id === accountId
const target = computed(() => `${barberId.value}:${date.value}`)
const ready = computed(() => !!slots.value && loadedTarget.value === target.value)
async function load() {
  if (!current()) return
  await read.run(async signal => {
    const [shop, services, barbers] = await Promise.all([
      api<ShopDetail>(`/api/v1/shops/${shopId}`, { auth: false, signal }),
      api<ShopService[]>(`/api/v1/shops/${shopId}/services`, { auth: false, signal }),
      api<PublicBarber[]>(`/api/v1/shops/${shopId}/barbers?serviceId=${serviceId}`, { auth: false, signal })])
    const service = services.find(s => s.id === serviceId && s.serviceType === 'HAIRCUT')
    if (!service) throw new Error('理发服务当前不可见或已下架，旧单请到本人订单处理。')
    return { shop, service, barbers }
  }, value => { shop.value = value.shop; service.value = value.service; barbers.value = value.barbers; barberId.value = value.barbers[0]?.id || '' })
}
async function querySlots() {
  if (busy.value || !barberId.value) return
  busy.value = true; error.value = ''; message.value = ''; selected.value = ''
  const id = barberId.value, day = date.value
  try {
    const value = await api<BookingSlots>(`/api/v1/shops/${shopId}/booking-slots?${new URLSearchParams({ serviceId, barberId: id, date: day })}`, { auth: false })
    if (current()) { previous.value = slots.value || previous.value; slots.value = value; loadedTarget.value = `${id}:${day}` }
  } catch (e) { if (current()) { error.value = errorText(e); slots.value = undefined; loadedTarget.value = '' } }
  finally { if (current()) busy.value = false }
}
async function submit(replay = false) {
  if (busy.value) return
  error.value = ''; message.value = ''
  let item = pending.value
  try {
    if (!replay) {
      if (item || !ready.value || !slots.value) throw new Error('请先核对原提交，或查询当前人员和日期的候选。')
      item = pendingBooking.start(accountId, bookingBody(serviceId, barberId.value, selected.value, slots.value, note.value)); pending.value = item
    }
    if (!item || item.accountId !== accountId) throw new Error('没有当前账号可重放的预约提交。')
  } catch (e) { error.value = errorText(e); return }
  busy.value = true
  let result: OrderDetail | undefined, failure: unknown
  try { result = await api<OrderDetail>('/api/v1/orders/bookings', { method: 'POST', body: item.body, idempotencyKey: item.key }) }
  catch (e) { failure = e }
  if (current()) {
    if (result) { item = { ...item, state: 'confirmed', orderId: result.id }; note.value = ''; message.value = `已确认预约订单 ${result.id}；原键重放仍返回原订单。` }
    else { const uncertain = !(failure instanceof ApiError) || failure.status === 0 || failure.status >= 500 || failure.code === 'RESPONSE_INVALID'; item = { ...item, state: uncertain ? 'pending' : 'rejected' }; error.value = errorText(failure) }
    pending.value = item
    try { pendingBooking.save(item) } catch { error.value += ' 恢复记录更新失败，请先到本人订单核对。' }
  }
  busy.value = false
  if (current() && failure instanceof ApiError && failure.code === 'QUOTE_CHANGED') {
    const original = error.value
    await querySlots()
    if (current()) error.value = `${original} 已重新查询当前候选；核对新价格/时长后清除原记录，再明确选择和提交。${error.value}`
  }
}
async function clearRecord() {
  if (busy.value || !await askDiscard('先核对本人订单。清除预约恢复记录不会取消服务端预约；不确定时另下新单可能产生第二笔订单。确认丢弃此记录和草稿？')) return
  pendingBooking.clear(); pending.value = null; selected.value = ''; note.value = ''; error.value = ''; message.value = '恢复记录已清除，请重新查询候选再选择。'; loadedTarget.value = ''
}
onBeforeRouteLeave(async () => { if (busy.value) return false; if (pending.value?.state === 'pending' || note.value) return askDiscard('预约未决记录会保留在本账号的当前标签页，离开前建议核对本人订单；其余控件草稿将丢弃。') })
onMounted(load)
</script>
<template>
  <RouterLink :to="`/shops/${shopId}`" class="back-link">← 返回商家</RouterLink><header class="page-heading"><span class="eyebrow">CAMPUS BOOKING</span><h1>预约理发服务</h1><p>{{ shop?.name || '正在读取商家' }} · {{ service?.name || '理发服务' }}</p></header><ErrorNotice :message="read.error.value || error" /><el-button v-if="read.error.value" @click="load">重新读取服务与人员</el-button><el-alert v-if="message" :title="message" type="success" :closable="false" role="status" />
  <section v-if="pending" class="surface panel pending-submission"><h2>{{ pending.state === 'confirmed' ? '原预约已确认' : pending.state === 'rejected' ? '原预约提交被拒绝' : '原预约结果待确认' }}</h2><p>原键：<code>{{ pending.key }}</code></p><p>人员{{ pending.body.barberId }} · {{ campusTime(pending.body.startAt) }}（上海）· ¥{{ money(pending.body.expectedPayableAmount) }}</p><p>重放只使用原键及冻结正文，不使用下方草稿；打印创建与预约创建分别隔离。</p><div class="action-row"><el-button :disabled="busy" @click="submit(true)">核对原预约（原键重放）</el-button><RouterLink v-if="pending.orderId" :to="`/orders/${pending.orderId}`" class="primary-link">查看预约订单 {{ pending.orderId }}</RouterLink><RouterLink to="/orders">核对本人订单</RouterLink><el-button :disabled="busy" @click="clearRecord">核对后清除预约记录</el-button></div></section>
  <section v-if="service" class="surface panel"><h2>选择人员与校园日期</h2><p class="field-help">上海今天至第6天，至少提前2小时；候选不预留资源，订单创建时系统再次核对。页面不调整时钟或绕过开始窗口。</p><fieldset class="booking-fields" :disabled="busy || !!pending"><div class="form-pair"><label>理发师<select v-model="barberId" aria-label="理发师"><option value="" disabled>选择人员</option><option v-for="b in barbers" :key="b.id" :value="b.id">{{ b.displayName }}</option></select></label><label>预约日期（上海）<select v-model="date" aria-label="预约日期"><option v-for="d in dates" :key="d" :value="d">{{ d }}</option></select></label></div><el-button :disabled="!barberId || busy || !!pending" @click="querySlots">查询预约时段</el-button></fieldset>
    <p v-if="slots && !ready" class="inline-error">人员或日期变化，请重新查询，旧候选不能提交。</p><template v-if="ready && slots"><p class="comparison" v-if="previous && (previous.priceAmount !== slots.priceAmount || previous.serviceVersion !== slots.serviceVersion || previous.durationMinutes !== slots.durationMinutes)">原价 ¥{{ money(previous.priceAmount) }}/{{ previous.durationMinutes }}分钟（版本{{ previous.serviceVersion }}）→ 当前 ¥{{ money(slots.priceAmount) }}/{{ slots.durationMinutes }}分钟（版本{{ slots.serviceVersion }}）</p><p>¥{{ money(slots.priceAmount) }} / 次 · {{ slots.durationMinutes }}分钟 · 服务版本{{ slots.serviceVersion }}</p><fieldset :disabled="busy || !!pending" class="booking-fields"><div class="slot-grid"><label v-for="slot in slots.slots" :key="slot.startAt" class="slot-choice"><input v-model="selected" type="radio" name="booking-slot" :value="slot.startAt" :aria-label="`预约 ${campusTime(slot.startAt)}`"><span>{{ campusTime(slot.startAt) }} — {{ campusTime(slot.endAt).slice(-5) }}</span></label></div><label>预约备注<textarea v-model="note" aria-label="预约备注" rows="2" placeholder="最多300个字符"></textarea></label><el-button type="primary" :disabled="busy || !!pending || !selected" @click="submit()">确认创建待支付预约</el-button></fieldset><el-empty v-if="!slots.slots.length" description="该组合暂无可预约时段，可尝试其他人员或日期" /></template><el-empty v-if="!barbers.length" description="暂无符合该服务的公开理发师" />
  </section>
</template>
