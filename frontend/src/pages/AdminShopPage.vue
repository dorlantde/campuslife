<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { api } from '../context'
import type { MerchantShop } from '../api/types'
import { approvalNames, memberNames, weekdays } from '../merchant/forms'
import { useLatestRead, useWrite } from '../merchant/useRequests'
import ErrorNotice from '../components/ErrorNotice.vue'
import { askDiscard } from '../shared/discard'
const route = useRoute(), read = useLatestRead(), write = useWrite()
const shop = ref<MerchantShop>(), reason = ref('')
const base = `/api/v1/admin/shops/${encodeURIComponent(String(route.params.shopId))}`
const pending = computed(() => shop.value?.approvalStatus === 'PENDING')
async function mayLeave() { return !write.busy.value && (!reason.value || await askDiscard('离开将丢弃未提交的审核原因。')) }
function load() { void read.run(signal => api<MerchantShop>(base, { signal }), value => { shop.value = value; reason.value = ''; write.reset() }) }
async function decide(approved: boolean) {
  if (!shop.value || !pending.value) return
  const body = approved ? { expectedVersion: shop.value.version } : { expectedVersion: shop.value.version, reason: reason.value }
  const value = await write.run(() => api<MerchantShop>(`${base}/${approved ? 'approve' : 'reject'}`, { method: 'POST', body }), approved ? '已批准入驻，店主已激活；店铺仍需配置并开启营业。' : '已拒绝申请并保存原因，商家可修改重提。')
  if (value) { shop.value = value; reason.value = '' }
}
onBeforeRouteLeave(mayLeave); onBeforeRouteUpdate(mayLeave); onMounted(load)
</script>
<template>
  <RouterLink to="/admin/shops" class="back-link">← 返回申请列表</RouterLink>
  <section class="management-heading"><div><span class="eyebrow">SHOP APPLICATION</span><h1>审核商家申请</h1></div><el-button @click="load" :disabled="write.busy.value" :loading="read.loading.value">重新载入（覆盖草稿）</el-button></section>
  <ErrorNotice :message="read.error.value || write.error.value" /><el-alert v-if="write.message.value" :title="write.message.value" type="success" :closable="false" class="notice" role="status" />
  <el-skeleton v-if="read.loading.value && !shop" class="surface panel" :rows="5" animated />
  <div v-if="shop" class="detail-grid"><section class="surface panel"><h2>{{ shop.name }}</h2><dl class="info-list"><dt>店铺编号</dt><dd>{{ shop.id }}</dd><dt>分类</dt><dd>{{ shop.category.name }}</dd><dt>位置</dt><dd>{{ shop.locationText }}</dd><dt>审核状态</dt><dd>{{ approvalNames[shop.approvalStatus] }}</dd><dt>店主状态</dt><dd>{{ memberNames[shop.memberStatus] }}</dd><dt>营业开关</dt><dd>{{ shop.businessEnabled ? '已开启' : '已关闭' }}</dd></dl><h3>申请介绍</h3><p class="pre-wrap">{{ shop.description || '未填写介绍' }}</p><el-alert v-if="shop.approvalReason" :title="`审核说明：${shop.approvalReason}`" type="info" :closable="false" /><h3 class="editor-divider">每周营业时间</h3><div v-for="(day, index) in weekdays" :key="day" class="hours-row"><span>{{ day }}</span><span>{{ shop.businessHours.filter(h => h.weekday === index + 1).map(h => `${h.startTime}–${h.endTime}`).join(' / ') || '未配置' }}</span></div></section>
    <section class="surface panel management-form"><h2>审核操作</h2><p class="muted">{{ pending ? '操作基于本页读取的申请版本。其他管理员处理后，须重新载入核对。' : '该店铺当前不是待审核状态，不能重复审核。' }}</p><fieldset :disabled="!pending || write.busy.value || write.blocked.value || read.loading.value"><el-button type="primary" @click="decide(true)" :disabled="!pending || write.blocked.value" :loading="write.busy.value">批准入驻申请</el-button><p class="field-help">批准后店主激活，营业开关仍关闭，由商家自行完成配置。</p><form @submit.prevent="decide(false)" class="editor-divider"><label for="review-reason">拒绝原因</label><el-input id="review-reason" v-model="reason" type="textarea" :rows="4" placeholder="1～300个字符，请说明需要补充的资料" required /><el-button type="danger" native-type="submit" :disabled="!pending || write.blocked.value || !reason.trim()" :loading="write.busy.value">填写原因并拒绝</el-button></form></fieldset><p class="quiet-note">暂停/恢复店铺、审核历史查询不在现有接口范围，本页只处理当前申请，不自动更改存量订单。</p></section>
  </div>
</template>
