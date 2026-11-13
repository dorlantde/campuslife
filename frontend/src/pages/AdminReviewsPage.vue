<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { api, session } from '../context'
import type { OwnReview, Page, Review } from '../api/types'
import { positiveId } from '../orders/rules'
import { reasonBody } from '../admin/rules'
import { errorText } from '../api/client'
import { askDiscard } from '../shared/discard'
import ErrorNotice from '../components/ErrorNotice.vue'
const route = useRoute(), input = ref(String(route.query.reviewId || '')), reason = ref(''), ack = ref(false), busy = ref(false), error = ref(''), result = ref<OwnReview>(), context = ref<Review>(), contextError = ref('')
const attempt = ref<{ id: string; body: { reason: string } }>()
const epoch = session.generation.value; let live = true
onScopeDispose(() => { live = false })
const current = () => live && epoch === session.generation.value
async function loadContext() {
  if (!route.query.shopId || !route.query.reviewId) return
  try {
    const shop = positiveId(String(route.query.shopId), '商家'), id = positiveId(String(route.query.reviewId), '评价'), page = String(route.query.page || '1')
    if (!/^[1-9][0-9]{0,9}$/.test(page) || Number(page) > 2147483647) throw new Error('公开评价页码无效')
    const value = await api<Page<Review>>(`/api/v1/shops/${shop}/reviews?page=${page}&size=10`, { auth: false })
    if (current()) { context.value = value.items.find(r => r.reviewId === id); if (!context.value) contextError.value = '未在该公开页找到目标内容，本次仅按ID处理；请另行核实。' }
  } catch (e) { if (current()) contextError.value = `未取得公开评价内容，仅按ID处理。${errorText(e)}` }
}
async function hide() {
  if (busy.value || !ack.value || result.value) return
  try { if (!attempt.value) attempt.value = { id: positiveId(input.value.trim(), '评价'), body: reasonBody(reason.value) } }
  catch (e) { error.value = errorText(e); return }
  busy.value = true; error.value = ''
  const original = attempt.value
  try { const value = await api<OwnReview>(`/api/v1/admin/reviews/${original.id}/hide`, { method: 'POST', body: original.body }); if (current()) result.value = value }
  catch (e) { if (current()) error.value = `${errorText(e)} 仅可手动重试原评价ID/原原因，不自动换目标。` }
  finally { if (current()) busy.value = false }
}
async function reset() {
  if (busy.value || !await askDiscard('清除本页处理草稿不会恢复已隐藏评价；不确定结果请先核实。确认继续？')) return
  attempt.value = undefined; result.value = undefined; context.value = undefined; reason.value = ''; ack.value = false; error.value = ''; contextError.value = ''
}
onBeforeRouteLeave(async () => { if (busy.value) return false; if (reason.value && !result.value) return askDiscard('处理原因或结果尚待核实，确认丢弃本页草稿并离开？') })
onMounted(loadContext)
</script>
<template><header class="page-heading"><span class="eyebrow">ADMIN REVIEW MODERATION</span><h1>评价隐藏处理</h1><p>从公开评价带入真实摘要，或仅按已核实的评价ID处理；没有全部评价列表/详情查询接口。</p></header><section class="surface panel admin-form"><ErrorNotice :message="error" /><p class="field-help">{{ contextError || '不编辑、删除或恢复评价；隐藏后不计公开评分，本人仍可读且不能重新评价。' }}</p><div v-if="context && context.reviewId === input.trim()" class="file-card"><strong>公开读取摘要 · 评价{{ context.reviewId }} · {{ context.rating }}分</strong><p>{{ context.reviewerNickname }}</p><p class="pre-wrap">{{ context.content }}</p></div><p v-else>当前未取得目标公开内容：仅按评价ID核实，页面不会合成摘要。</p><fieldset :disabled="busy || !!attempt || !!result"><label>评价编号<input v-model="input" aria-label="评价编号" inputmode="numeric"></label><label>隐藏原因<textarea v-model="reason" aria-label="隐藏原因" rows="3" placeholder="1～300个字符"></textarea></label></fieldset><label class="acknowledgement"><input v-model="ack" type="checkbox" :disabled="busy || !!result" aria-label="已核实评价及隐藏影响">已核实评价ID/内容和原因，确认隐藏且不提供恢复入口</label><div class="action-row"><el-button type="primary" :disabled="!ack || !!result" :loading="busy" @click="hide">{{ attempt ? '重试原隐藏请求' : '确认隐藏评价' }}</el-button><el-button :disabled="busy" @click="reset">清除本页处理草稿</el-button></div><div v-if="result" class="file-card"><h2>已隐藏评价 {{ result.id }}</h2><p>{{ result.rating }}分 · {{ result.visibility }}</p><p class="pre-wrap">{{ result.content }}</p><p>后续公开请求与评分将排除此评价；不改变订单、退款或文件期限。</p></div></section></template>
