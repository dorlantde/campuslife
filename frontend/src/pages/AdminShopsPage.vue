<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api } from '../context'
import { router } from '../router'
import type { MerchantShop, Page, ApprovalStatus } from '../api/types'
import { approvalNames, memberNames } from '../merchant/forms'
import { useLatestRead } from '../merchant/useRequests'
import ErrorNotice from '../components/ErrorNotice.vue'
const route = useRoute(), read = useLatestRead()
const page = ref<Page<MerchantShop>>(), status = ref('')
function load() {
  status.value = typeof route.query.approvalStatus === 'string' ? route.query.approvalStatus : ''
  const raw = String(route.query.page || '1')
  const query = new URLSearchParams({ page: /^[1-9][0-9]*$/.test(raw) && Number(raw) <= 2147483647 ? raw : '1', size: '20' })
  if (status.value) query.set('approvalStatus', status.value)
  page.value = undefined
  void read.run(signal => api<Page<MerchantShop>>(`/api/v1/admin/shops?${query}`, { signal }), value => { page.value = value })
}
function search(next = 1) { const query: Record<string, string> = {}; if (status.value) query.approvalStatus = status.value; if (next > 1) query.page = String(next); void router.push({ path: '/admin/shops', query }) }
watch(() => route.fullPath, load, { immediate: true })
</script>
<template>
  <section class="management-heading"><div><span class="eyebrow">ADMIN WORKSPACE</span><h1>商家入驻审核</h1><p>查看真实申请资料，读取最新状态后批准或填写原因拒绝。</p></div><el-button @click="load" :loading="read.loading.value">刷新申请列表</el-button></section>
  <form @submit.prevent="search(1)" class="surface search-bar"><label for="approval-filter">审核状态</label><select id="approval-filter" v-model="status"><option value="">全部状态</option><option v-for="(label, key) in approvalNames" :key="key" :value="key">{{ label }}</option></select><el-button native-type="submit" type="primary">筛选申请</el-button></form>
  <ErrorNotice :message="read.error.value" /><el-skeleton v-if="read.loading.value" class="surface panel" :rows="6" animated />
  <section v-else-if="page" class="surface panel"><div class="section-title"><h2>申请列表</h2><span class="muted">共 {{ page.total }} 家</span></div><div v-if="page.items.length" class="table-scroll"><table class="management-table"><thead><tr><th>店铺</th><th>分类/位置</th><th>审核状态</th><th>店主状态</th><th>操作</th></tr></thead><tbody><tr v-for="shop in page.items" :key="shop.id"><td>{{ shop.name }}<small>编号 {{ shop.id }}</small></td><td>{{ shop.category.name }}<small>{{ shop.locationText }}</small></td><td>{{ approvalNames[shop.approvalStatus] }}</td><td>{{ memberNames[shop.memberStatus] }}</td><td><RouterLink :to="`/admin/shops/${shop.id}`" class="table-link">查看申请</RouterLink></td></tr></tbody></table></div><el-empty v-else description="没有符合筛选条件的申请" /><el-pagination v-if="page.totalPages > 1" class="pagination" layout="prev, pager, next" :page-size="page.size" :total="page.total" :current-page="page.page" @current-change="search" /></section>
</template>
