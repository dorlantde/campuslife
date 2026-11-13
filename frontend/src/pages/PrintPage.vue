<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { RouterLink, onBeforeRouteLeave, useRoute } from 'vue-router'
import { api, session } from '../context'
import type { FileAsset, OrderDetail, PrintQuote, PrintSpec, ShopDetail, ShopService } from '../api/types'
import type { PdfDownload } from '../api/client'
import { ApiError, errorText } from '../api/client'
import { savePdf } from '../api/files'
import { campusTime, money } from '../shared/format'
import { askDiscard } from '../shared/discard'
import { copiesValue, positiveId, printBody, quoteMatches, quoteRequest } from '../orders/rules'
import { pendingPrint, type PendingPrint } from '../orders/pending'
import ErrorNotice from '../components/ErrorNotice.vue'
const route = useRoute(), serviceId = String(route.params.serviceId), shopId = String(route.params.shopId)
const accountId = session.current.value?.account.id || '', epoch = session.generation.value
const shop = ref<ShopDetail>(), service = ref<ShopService>(), error = ref(''), message = ref('')
const file = ref<FileAsset>(), fileInput = ref(''), selectedFile = ref<File>(), copies = ref('1'), color = ref<'BW' | 'COLOR'>('BW'), side = ref<'SINGLE' | 'DOUBLE'>('SINGLE'), note = ref('')
const quote = ref<PrintQuote>(), previous = ref<PrintQuote>(), pending = ref<PendingPrint | null>(pendingPrint.read(accountId))
const loading = ref(false), busy = ref(false), downloading = ref(false)
let live = true
onScopeDispose(() => { live = false })
const current = () => live && !!accountId && epoch === session.generation.value && session.current.value?.account.id === accountId
function spec(): PrintSpec {
  if (!file.value || fileInput.value.trim() !== file.value.id) throw new Error('文件编号已变化，请先读取并核对该文件。')
  return { serviceId, fileId: positiveId(file.value.id), paperSize: 'A4', colorMode: color.value, sideMode: side.value, copies: copiesValue(copies.value) }
}
const quoteCurrent = computed(() => { try { return !!quote.value && quoteMatches(quote.value, spec()) } catch { return false } })
const sameTarget = computed(() => pending.value?.body.serviceId === serviceId)
function chooseFile(e: Event) { selectedFile.value = (e.target as HTMLInputElement).files?.[0]; file.value = undefined; quote.value = undefined; fileInput.value = '' }
async function load() {
  if (!current()) return
  loading.value = true; error.value = ''
  try {
    const [s, services] = await Promise.all([api<ShopDetail>(`/api/v1/shops/${shopId}`, { auth: false }), api<ShopService[]>(`/api/v1/shops/${shopId}/services`, { auth: false })])
    if (!current()) return
    const target = services.find(x => x.id === serviceId && x.serviceType === 'PRINT')
    if (!target) throw new Error('该打印服务当前不可见或已下架。已有订单请到“我的打印订单”处理。')
    shop.value = s; service.value = target
    if (pending.value && sameTarget.value) {
      const body = pending.value.body
      fileInput.value = body.fileId; copies.value = String(body.copies); color.value = body.colorMode; side.value = body.sideMode; note.value = body.note
      await readFile()
    }
  } catch (e) { if (current()) error.value = errorText(e) }
  finally { if (current()) loading.value = false }
}
async function readFile() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const result = await api<FileAsset>(`/api/v1/files/${positiveId(fileInput.value.trim())}`)
    if (current()) { file.value = result; fileInput.value = result.id; quote.value = undefined }
  } catch (e) { if (current()) { error.value = errorText(e); file.value = undefined; quote.value = undefined } }
  finally { if (current()) busy.value = false }
}
async function upload() {
  if (busy.value || pending.value) return
  error.value = ''; message.value = ''
  if (!selectedFile.value || !/\.pdf$/i.test(selectedFile.value.name) || selectedFile.value.size === 0 || selectedFile.value.size > 20 * 1024 * 1024) { error.value = '请选择非空PDF文件，大小不超过20 MiB；解析/页数/加密检查以服务器为准。'; return }
  busy.value = true
  try {
    const form = new FormData(); form.append('file', selectedFile.value)
    const result = await api<FileAsset>('/api/v1/files', { method: 'POST', form })
    if (current()) { file.value = result; fileInput.value = result.id; quote.value = undefined; message.value = '上传成功。请按需要选择规格并获取报价。' }
  } catch (e) { if (current()) error.value = `${errorText(e)} 若响应丢失，本次上传可能已保存；不会自动重复上传，未引用文件按原规则到期清理。` }
  finally { if (current()) busy.value = false }
}
async function getQuote(original?: PrintSpec) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    const next = await api<PrintQuote>('/api/v1/print-quotes', { method: 'POST', body: quoteRequest(original || spec()) })
    if (current()) { previous.value = quote.value || previous.value; quote.value = next }
  } catch (e) { if (current()) { error.value = errorText(e); quote.value = undefined } }
  finally { if (current()) busy.value = false }
}
async function submit(replay = false) {
  if (busy.value) return
  error.value = ''; message.value = ''
  let item = pending.value
  try {
    if (!replay) {
      if (item || !quote.value || !quoteCurrent.value) throw new Error('请核对原提交，或先获取当前规格报价。')
      item = pendingPrint.start(accountId, printBody(quote.value, note.value)); pending.value = item
    }
    if (!item || item.accountId !== accountId) throw new Error('没有当前账号可重试的原提交。')
  } catch (e) { error.value = errorText(e); return }
  busy.value = true
  let result: OrderDetail | undefined, failure: unknown
  try { result = await api<OrderDetail>('/api/v1/orders/prints', { method: 'POST', body: item.body, idempotencyKey: item.key }) }
  catch (e) { failure = e }
  if (current()) {
    if (result) {
      item = { ...item, state: 'confirmed', orderId: result.id }; message.value = `已确认订单 ${result.id}。重复原键提交不会产生第二笔订单。`
    } else {
      const uncertain = !(failure instanceof ApiError) || failure.status === 0 || failure.status >= 500 || failure.code === 'RESPONSE_INVALID'
      item = { ...item, state: uncertain ? 'pending' : 'rejected' }; error.value = errorText(failure)
    }
    pending.value = item
    try { pendingPrint.save(item) } catch { error.value += ' 恢复记录更新失败，请先到订单列表核对；不要重新下单。' }
  }
  busy.value = false
  if (current() && failure instanceof ApiError && failure.code === 'QUOTE_CHANGED') {
    const originalError = error.value
    await getQuote(item.body)
    if (current()) error.value = `${originalError} 已重新查询报价，请对比后明确清除旧提交，再确认新单。${error.value ? ` ${error.value}` : ''}`
  }
}
async function clearRecord() {
  if (busy.value || !await askDiscard('请先到订单列表核对原提交。清除恢复记录不会取消服务端订单；结果不确定时，新提交可能形成另一笔订单。确认丢弃本页草稿/记录？')) return
  pendingPrint.clear(); pending.value = null; error.value = ''; message.value = '本页恢复记录已清除。请重新核对规格和报价，再确认下一笔订单。'
}
async function download() {
  if (!file.value || downloading.value) return
  downloading.value = true; error.value = ''
  try { const pdf = await api<PdfDownload>(`/api/v1/files/${file.value.id}/content`, { response: 'pdf' }); if (current()) { savePdf(pdf.blob, pdf.filename); message.value = 'PDF已交给浏览器保存，请查看下载结果。' } }
  catch (e) { if (current()) error.value = errorText(e) }
  finally { if (current()) downloading.value = false }
}
onBeforeRouteLeave(async () => { if (busy.value) return false; if (pending.value?.state === 'pending') return askDiscard('原下单结果尚未确认。恢复记录会保留在当前账号的本标签页中，离开前建议先核对订单；其他未保存控件草稿将丢弃。') })
onMounted(load)
</script>
<template>
  <RouterLink :to="`/shops/${shopId}`" class="back-link">← 返回商家</RouterLink>
  <header class="page-heading"><span class="eyebrow">CAMPUS PRINT</span><h1>打印学习资料</h1><p>{{ shop?.name || '正在读取商家' }} · {{ service?.name || 'PDF打印' }}</p></header>
  <ErrorNotice :message="error" /><el-alert v-if="message" :title="message" type="success" :closable="false" role="status" />
  <el-button v-if="!service && !loading" @click="load">重新读取服务</el-button>
  <section v-if="pending" class="surface panel pending-submission"><h2>{{ pending.state === 'confirmed' ? '原提交已确认' : pending.state === 'rejected' ? '原提交被拒绝，请核对后再决定' : '原提交结果待确认' }}</h2><p>原键：<code>{{ pending.key }}</code></p><p>文件 {{ pending.body.fileId }} · {{ pending.body.copies }}份 · 原应付 ¥{{ money(pending.body.expectedPayableAmount) }}。原正文已冻结，重试不使用下方新草稿。</p><p v-if="!sameTarget">此记录属于另一打印服务，仍可核对原提交；不会向当前服务替换正文。</p><div class="action-row"><el-button :disabled="busy" @click="submit(true)">核对原提交（原键重放）</el-button><RouterLink v-if="pending.orderId" :to="`/orders/${pending.orderId}`" class="primary-link">查看订单 {{ pending.orderId }}</RouterLink><RouterLink to="/orders">核对我的打印订单</RouterLink><el-button :disabled="busy" @click="clearRecord">核对后清除恢复记录</el-button></div></section>
  <div v-if="service" class="print-grid">
    <section class="surface panel"><h2>1. 准备PDF</h2><p class="field-help">20 MiB以内、1～500页，不支持加密PDF。文件不会保存在浏览器存储中。</p>
      <fieldset :disabled="busy || !!pending"><label>选择PDF文件<input aria-label="选择PDF文件" type="file" accept="application/pdf,.pdf" @change="chooseFile"></label><el-button :disabled="!selectedFile || busy || !!pending" @click="upload">上传PDF</el-button><div class="or-divider">或复用已知的本人文件</div><label>本人文件编号<input v-model="fileInput" aria-label="本人文件编号" inputmode="numeric" placeholder="输入本人有效文件ID"></label><el-button :disabled="busy || !!pending" @click="readFile">读取本人文件</el-button></fieldset>
      <div v-if="file" class="file-card"><strong>{{ file.originalName }}</strong><p>文件ID {{ file.id }} · {{ file.pageCount }}页 · {{ (file.byteSize / 1024).toFixed(1) }} KiB</p><p>{{ file.expiresAt ? `有效至 ${campusTime(file.expiresAt)}（上海）` : '关联订单保护中' }}</p><el-button :loading="downloading" @click="download">下载本人PDF</el-button></div>
    </section>
    <section class="surface panel"><h2>2. 选择规格并确认报价</h2><fieldset :disabled="busy || !!pending"><div class="form-pair"><label>纸张<input value="A4" disabled></label><label>色彩<select v-model="color" aria-label="色彩"><option value="BW">黑白</option><option value="COLOR">彩色</option></select></label><label>单双面<select v-model="side" aria-label="单双面"><option value="SINGLE">单面</option><option value="DOUBLE">双面</option></select></label><label>份数<input v-model="copies" aria-label="份数" inputmode="numeric"></label></div><label>订单备注<textarea v-model="note" aria-label="订单备注" rows="2" placeholder="最多300个字符"></textarea></label><el-button :disabled="busy || !file || !!pending" @click="getQuote()">获取打印报价</el-button></fieldset>
      <div v-if="quote" class="quote-card"><p v-if="previous && (previous.payableAmount !== quote.payableAmount || previous.priceVersion !== quote.priceVersion)" class="comparison">原报价 ¥{{ money(previous.payableAmount) }}（版本{{ previous.priceVersion }}）→ 新报价 ¥{{ money(quote.payableAmount) }}（版本{{ quote.priceVersion }}），请重新核对。</p><p>{{ quote.pageCount }}页 × {{ quote.copies }}份 · {{ quote.sheetCount }}张纸 · ¥{{ money(quote.unitPriceAmount) }}/印刷面</p><p>规格版本 {{ quote.priceVersion }} · 优惠 ¥{{ money(quote.discountAmount) }}</p><div class="quote-total">应付 <strong>¥{{ money(quote.payableAmount) }}</strong></div><p v-if="!quoteCurrent">规格或文件已变化，请重新报价。</p><p class="field-help">报价不锁价；下单时由系统重新核对。当前上海营业时段内才可创建，营业状态以服务端为准。</p><el-button type="primary" :disabled="busy || !!pending || !quoteCurrent" :loading="busy" @click="submit()">确认创建待支付订单</el-button></div>
    </section>
  </div>
</template>
