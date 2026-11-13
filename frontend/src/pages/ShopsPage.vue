<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api } from '../context'
import { router } from '../router'
import type { Category, ShopSummary, Page } from '../api/types'
import { errorText } from '../api/client'
import ErrorNotice from '../components/ErrorNotice.vue'
const route = useRoute()
const categories = ref<Category[]>([])
const page = ref<Page<ShopSummary>>()
const keyword = ref('')
const categoryId = ref('')
const error = ref('')
const categoryError = ref('')
const loading = ref(false)
let controller: AbortController | undefined
let sequence = 0
function pageNumber(): number { const n = String(route.query.page || '1'); return /^[1-9][0-9]*$/.test(n) && Number(n) <= 2147483647 ? Number(n) : 1 }
async function loadCategories() {
  categoryError.value = ''
  try { categories.value = await api<Category[]>('/api/v1/shop-categories', { auth: false }) }
  catch(e) { categoryError.value = errorText(e) }
}
async function load() {
  controller?.abort(); controller = new AbortController()
  const seq = ++sequence
  loading.value = true; error.value = ''; page.value = undefined
  keyword.value = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  categoryId.value = typeof route.query.categoryId === 'string' ? route.query.categoryId : ''
  const query = new URLSearchParams({ page: String(pageNumber()), size: '12' })
  if (keyword.value.trim()) query.set('keyword', keyword.value.trim())
  if (categoryId.value) query.set('categoryId', categoryId.value)
  try { const result = await api<Page<ShopSummary>>(`/api/v1/shops?${query}`, { auth: false, signal: controller.signal }); if (seq === sequence) page.value = result }
  catch(e) { if (seq === sequence) error.value = errorText(e) }
  finally { if (seq === sequence) loading.value = false }
}
function search(next = 1) {
  const query: Record<string, string> = {}
  if (keyword.value.trim()) query.keyword = keyword.value.trim()
  if (categoryId.value) query.categoryId = categoryId.value
  if (next > 1) query.page = String(next)
  void router.push({ path: '/shops', query })
  if (route.path === '/shops' && JSON.stringify(query) === JSON.stringify(route.query)) void load()
}
watch(() => route.fullPath, load, { immediate: true })
void loadCategories()
onUnmounted(() => { sequence++; controller?.abort() })
</script>
<template>
  <section class="discovery-hero"><div><span class="eyebrow">XINGHAI CAMPUS · 星海大学</span><h1>校园日常，<br><span>就在身边。</span></h1><p>发现校园里的理发与打印服务，<br>看看大家的评价，再安排你的下一站。</p><div class="hero-badges"><span>校园服务</span><span>真实订单评价</span><span>上海时区</span></div></div><div class="hero-visual" aria-hidden="true"><span class="hero-orbit"></span><div class="visual-card card-print"><span class="visual-icon">▤</span><strong>校园打印</strong><small>每一页，都有安排</small></div><div class="visual-card card-hair"><span class="visual-icon">✂</span><strong>清爽出发</strong><small>发现身边的理发服务</small></div><span class="decor-dot dot-one"></span><span class="decor-dot dot-two"></span></div></section>
  <section aria-labelledby="shops-title">
    <div class="section-title"><div><span class="eyebrow">EXPLORE NEARBY</span><h2 id="shops-title">发现商家</h2></div><span v-if="page" class="muted">共 {{ page.total }} 家商家</span></div>
    <form @submit.prevent="search(1)" class="surface search-bar"><label class="sr-only" for="shop-keyword">商家名称</label><el-input id="shop-keyword" v-model="keyword" placeholder="搜索商家名称" clearable :maxlength="80" /><label class="sr-only" for="shop-category">商家分类</label><el-select id="shop-category" v-model="categoryId" placeholder="全部分类" clearable aria-label="商家分类"><el-option label="全部分类" value="" /><el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" /></el-select><el-button type="primary" native-type="submit" :loading="loading">查找商家</el-button></form>
    <div v-if="categoryError" class="inline-error" role="alert">{{ categoryError }} <el-button text @click="loadCategories">重试分类</el-button></div>
    <ErrorNotice :message="error" /><el-button v-if="error" @click="load">重新加载商家</el-button>
    <div v-if="loading" class="shop-grid" aria-label="正在加载商家"><div v-for="i in 3" :key="i" class="surface panel"><el-skeleton :rows="5" animated /></div></div>
    <div v-else-if="page?.items.length" class="shop-grid">
      <RouterLink v-for="shop in page.items" :key="shop.id" :to="`/shops/${shop.id}`" class="surface shop-card">
        <div class="shop-cover" :class="shop.category.code === 'PRINT' ? 'print-cover' : 'hair-cover'"><span class="category-label">{{ shop.category.name }}</span><span class="cover-symbol" aria-hidden="true">{{ shop.category.code === 'PRINT' ? '▤' : '✂' }}</span><span class="cover-caption">XINGHAI · CAMPUS LIFE</span></div>
        <div class="shop-card-body"><div class="card-title-row"><h3>{{ shop.name }}</h3><span class="status" :class="{ open: shop.openNow }">{{ shop.openNow ? '营业中' : '休息中' }}</span></div><p class="location">{{ shop.locationText }}</p><div class="card-meta"><span class="rating">{{ shop.rating === null ? '暂无评分' : `★ ${shop.rating.toFixed(2)}` }} <small>· {{ shop.reviewCount }} 条评价</small></span><span class="card-arrow" aria-hidden="true">↗</span></div></div>
      </RouterLink>
    </div>
    <el-empty v-else-if="page" description="暂未找到匹配商家，试试其他名称或分类。" />
    <el-pagination v-if="page && page.totalPages > 1" class="pagination" layout="prev, pager, next" :current-page="page.page" :page-size="page.size" :total="page.total" @current-change="search" />
    <p class="section-footnote">营业状态依据校园上海时间计算，展示营业中不代表当前有可预约时段。</p>
  </section>
</template>
