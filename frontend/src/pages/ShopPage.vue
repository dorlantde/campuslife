<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api, session } from '../context'
import type { ShopDetail, ShopService, Page, Review } from '../api/types'
import { campusTime, money } from '../shared/format'
import { errorText } from '../api/client'
import ErrorNotice from '../components/ErrorNotice.vue'
const route = useRoute()
const shop = ref<ShopDetail>()
const services = ref<ShopService[]>([])
const reviews = ref<Page<Review>>()
const error = ref('')
const serviceError = ref('')
const reviewError = ref('')
const loading = ref(false)
const serviceLoading = ref(false)
const reviewLoading = ref(false)
const base = `/api/v1/shops/${encodeURIComponent(String(route.params.shopId))}`
const controller = new AbortController()
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
async function loadServices() {
  serviceLoading.value = true; serviceError.value = ''
  try { services.value = await api<ShopService[]>(`${base}/services`, { auth: false, signal: controller.signal }) }
  catch(e) { serviceError.value = errorText(e) }
  finally { serviceLoading.value = false }
}
async function loadReviews(page = 1) {
  if (reviewLoading.value) return
  reviewLoading.value = true; reviewError.value = ''
  try { reviews.value = await api<Page<Review>>(`${base}/reviews?page=${page}&size=10`, { auth: false, signal: controller.signal }) }
  catch(e) { reviewError.value = errorText(e) }
  finally { reviewLoading.value = false }
}
async function load() {
  loading.value = true; error.value = ''
  try { shop.value = await api<ShopDetail>(base, { auth: false, signal: controller.signal }); await Promise.all([loadServices(), loadReviews()]) }
  catch(e) { error.value = errorText(e) }
  finally { loading.value = false }
}
onMounted(load)
onUnmounted(() => controller.abort())
</script>
<template>
  <RouterLink to="/shops" class="back-link">← 返回商家目录</RouterLink><ErrorNotice :message="error" /><el-button v-if="error" @click="load">重新加载详情</el-button><el-skeleton v-if="loading && !shop" class="surface panel" :rows="8" animated />
  <template v-if="shop">
    <section class="surface detail-hero"><div class="detail-icon" aria-hidden="true">{{ shop.category.code === 'PRINT' ? '▤' : '✂' }}</div><div class="detail-intro"><span class="eyebrow">{{ shop.category.name }} · 星海校园</span><h1>{{ shop.name }}</h1><p>{{ shop.locationText }}</p><div class="detail-meta"><span class="status" :class="{ open: shop.openNow }">{{ shop.openNow ? '营业中' : '休息中' }}</span><span class="rating">{{ shop.rating === null ? '暂无评分' : `★ ${shop.rating.toFixed(2)}` }}</span><span class="muted">{{ shop.reviewCount }} 条公开评价</span></div></div></section>
    <div class="detail-grid"><div>
      <section class="surface panel"><div class="section-title"><h2>店内服务</h2><span class="muted">{{ services.length }} 项</span></div><ErrorNotice :message="serviceError" /><el-button v-if="serviceError" @click="loadServices">重试服务</el-button><el-skeleton v-if="serviceLoading" :rows="3" animated /><div v-for="s in services" :key="s.id" class="service-row"><div><h3>{{ s.name }}</h3><p>{{ s.description || '暂无服务说明' }}</p><span v-if="s.durationMinutes !== null" class="muted">约 {{ s.durationMinutes }} 分钟</span></div><div class="service-price">¥{{ money(s.priceAmount) }}<small>{{ s.serviceType === 'PRINT' ? '参考起价' : '/ 次' }}</small><RouterLink v-if="s.serviceType === 'PRINT' && (!session.current.value || session.current.value.account.role === 'STUDENT')" :to="`/shops/${shop.id}/print/${s.id}`" class="primary-link">选择PDF打印</RouterLink><RouterLink v-if="s.serviceType === 'HAIRCUT' && (!session.current.value || session.current.value.account.role === 'STUDENT')" :to="`/shops/${shop.id}/book/${s.id}`" class="primary-link">预约理发</RouterLink></div></div><el-empty v-if="!serviceLoading && !serviceError && !services.length" description="暂无上架服务" /><p class="quiet-note">打印服务可进入PDF报价和下单；参考起价不等于规格报价。理发服务可查询人员、校园日期和真实候选时段。</p></section>
      <section class="surface panel review-panel"><div class="section-title"><h2>同学们的评价</h2><span class="muted">{{ reviews?.total ?? '—' }} 条</span></div><ErrorNotice :message="reviewError" /><el-button v-if="reviewError" @click="loadReviews(reviews?.page || 1)">重试评价</el-button><el-skeleton v-if="reviewLoading" :rows="4" animated /><article v-for="review in reviews?.items || []" :key="review.reviewId" class="review-item"><div class="review-heading"><strong>{{ review.reviewerNickname }}</strong><span class="rating">{{ '★'.repeat(review.rating) }}<span class="muted">{{ '☆'.repeat(5 - review.rating) }}</span></span></div><p class="review-content">{{ review.content }}</p><div class="review-meta"><time>{{ campusTime(review.createdAt) }} · 上海</time><span>评价 ID {{ review.reviewId }}</span><RouterLink v-if="session.current.value?.account.role === 'ADMIN'" :to="{ path: '/admin/reviews', query: { shopId: shop.id, reviewId: review.reviewId, page: String(reviews?.page || 1) } }">核实此评价</RouterLink></div></article><el-empty v-if="reviews && !reviews.items.length && !reviewLoading" description="还没有公开评价" /><el-pagination v-if="reviews && reviews.totalPages > 1" class="pagination" layout="prev, pager, next" :current-page="reviews.page" :page-size="reviews.size" :total="reviews.total" :disabled="reviewLoading" @current-change="loadReviews" /></section>
    </div><aside><section class="surface panel"><h2>关于商家</h2><p class="pre-wrap">{{ shop.description || '商家暂未填写介绍。' }}</p><h3>营业时间</h3><p class="field-help">校园时间 · Asia/Shanghai</p><div class="hours-row" v-for="(day, index) in weekdays" :key="day"><span>{{ day }}</span><span>{{ shop.businessHours.filter(h => h.weekday === index + 1).map(h => `${h.startTime}–${h.endTime}`).join(' / ') || '未设置时段' }}</span></div><p class="quiet-note">营业中仅反映营业开关和当前时段，不保证可预约或可下单。</p></section></aside></div>
  </template>
</template>
