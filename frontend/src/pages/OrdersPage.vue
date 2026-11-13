<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api } from '../context'
import type { OrderSummary, Page, OrderStatus } from '../api/types'
import { statusNames, progressNames, paymentNames } from '../orders/rules'
import { campusTime, money } from '../shared/format'
import { useLatestRead } from '../merchant/useRequests'
import ErrorNotice from '../components/ErrorNotice.vue'
const props = defineProps<{ merchant?: boolean }>()
const read = useLatestRead(), result = ref<Page<OrderSummary>>(), status = ref<OrderStatus | ''>('')
const type = ref<'PRINT' | 'BOOKING' | ''>('')
const base = props.merchant ? '/merchant/orders' : '/orders'
async function load(page = 1) {
  const params = new URLSearchParams({ page: String(page), size: '20' })
  if (type.value) params.set('orderType', type.value)
  if (status.value) params.set('status', status.value)
  await read.run(signal => api<Page<OrderSummary>>(`/api/v1${base}?${params}`, { signal }), value => { result.value = value })
}
onMounted(() => load())
</script>
<template>
  <header class="page-heading"><span class="eyebrow">{{ merchant ? 'MERCHANT TASKS' : 'MY ORDERS' }}</span><h1>{{ merchant ? '本店订单任务' : '我的订单' }}</h1><p>{{ merchant ? '查询本店预约与打印任务，按真实订单阶段履约。' : '查看下单快照、模拟支付、取件与评价。' }}</p></header>
  <section class="surface panel"><div class="action-row"><label>订单类型<select v-model="type" aria-label="订单类型" :disabled="read.loading.value" @change="load()"><option value="">全部类型</option><option value="PRINT">打印</option><option value="BOOKING">预约</option></select></label><label>订单状态<select v-model="status" aria-label="订单状态" :disabled="read.loading.value" @change="load()"><option value="">全部状态</option><option v-for="(name, code) in statusNames" :key="code" :value="code">{{ name }}</option></select></label><el-button :loading="read.loading.value" @click="load()">刷新订单列表</el-button><RouterLink v-if="!merchant" to="/shops">去发现校园服务</RouterLink></div><p class="field-help">读取本人/本店两类历史订单；GET不会自动关单或退款。</p><ErrorNotice :message="read.error.value" />
    <p v-if="result" class="muted">共{{ result.total }}笔 · 第{{ result.page }}页</p><div class="table-scroll"><table class="management-table orders-table"><thead><tr><th>订单 / 商家</th><th>状态</th><th>金额</th><th>创建时间（上海）</th><th></th></tr></thead><tbody><tr v-for="o in result?.items || []" :key="o.id"><td><strong>{{ o.serviceName }}</strong><p>{{ o.shopName }} · {{ o.orderType === 'PRINT' ? '打印' : '预约' }}</p><small v-if="o.bookingStartAt">{{ campusTime(o.bookingStartAt) }} — {{ o.bookingEndAt ? campusTime(o.bookingEndAt).slice(-5) : '—' }}</small><small>{{ o.orderNo }} · ID {{ o.id }}</small></td><td>{{ statusNames[o.status] }}<p>{{ o.printingProgress ? progressNames[o.printingProgress] || o.printingProgress : '' }}</p><small>{{ paymentNames[o.paymentStatus] }}</small></td><td>¥{{ money(o.payableAmount) }}</td><td>{{ campusTime(o.createdAt) }}</td><td><RouterLink :to="`${base}/${o.id}`">查看详情</RouterLink></td></tr></tbody></table></div><el-empty v-if="result && !result.items.length && !read.loading.value" description="暂无符合条件的订单" /><el-pagination v-if="result && result.totalPages > 1" layout="prev, pager, next" :total="result.total" :page-size="result.size" :current-page="result.page" :disabled="read.loading.value" @current-change="load" />
  </section>
</template>
