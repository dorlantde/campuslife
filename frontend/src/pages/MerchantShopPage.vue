<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '../context'
import type { MerchantShop } from '../api/types'
import { useLatestRead } from '../merchant/useRequests'
import { approvalNames, memberNames } from '../merchant/forms'
import ShopProfilePanel from '../merchant/ShopProfilePanel.vue'
import BusinessHoursPanel from '../merchant/BusinessHoursPanel.vue'
import ServicesPanel from '../merchant/ServicesPanel.vue'
import PrintPricesPanel from '../merchant/PrintPricesPanel.vue'
import BarbersPanel from '../merchant/BarbersPanel.vue'
import SchedulePanel from '../merchant/SchedulePanel.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import { askDiscard } from '../shared/discard'
const shop = ref<MerchantShop>()
const read = useLatestRead()
const active = ref('profile'), dirty = ref(false), busy = ref(false)
const readEpoch = ref(0)
const editable = computed(() => shop.value?.approvalStatus === 'APPROVED' && shop.value?.memberStatus === 'ACTIVE')
const tabs = computed(() => [
  { key: 'profile', label: '店铺资料' },
  ...(editable.value ? [{ key: 'hours', label: '营业时间' }, { key: 'services', label: '服务目录' },
    ...(shop.value?.category.code === 'PRINT' ? [{ key: 'prices', label: '打印价目' }] : [{ key: 'barbers', label: '理发师与绑定' }, { key: 'schedule', label: '按日排班' }])] : []),
])
async function mayLeave() { return !busy.value && (!dirty.value || await askDiscard('当前表单有未保存的修改，切换将丢弃草稿。')) }
async function changeTab(key: string) { if (key !== active.value && await mayLeave()) { dirty.value = false; busy.value = false; active.value = key } }
async function load() {
  if (!await mayLeave()) return
  void read.run(signal => api<MerchantShop>('/api/v1/merchant/shop', { signal }), value => { shop.value = value; dirty.value = false; active.value = 'profile'; readEpoch.value++ })
}
function saved(value: MerchantShop) { shop.value = value }
watch(editable, allowed => { if (!allowed) active.value = 'profile' })
onBeforeRouteLeave(mayLeave)
onMounted(load)
</script>
<template>
  <section class="management-heading"><div><span class="eyebrow">MERCHANT WORKSPACE</span><h1>店铺管理</h1><p>维护经营资料与服务安排，实际接单资格由系统核对。</p></div><el-button @click="load" :disabled="busy" :loading="read.loading.value">重新读取店铺</el-button></section>
  <ErrorNotice :message="read.error.value" /><el-skeleton v-if="read.loading.value" class="surface panel" :rows="5" animated />
  <template v-else-if="shop">
    <div class="surface shop-status-strip"><strong>{{ shop.name }}</strong><span class="role-pill">{{ shop.category.name }}</span><span class="status" :class="{ open: shop.approvalStatus === 'APPROVED' }">{{ approvalNames[shop.approvalStatus] }}</span><span class="muted">店主{{ memberNames[shop.memberStatus] }} · 店铺编号 {{ shop.id }}</span></div>
    <nav class="management-tabs" aria-label="店铺配置"><button v-for="tab in tabs" :key="tab.key" :class="{ selected: active === tab.key }" :aria-current="active === tab.key ? 'page' : undefined" :disabled="busy" @click="changeTab(tab.key)">{{ tab.label }}</button></nav>
    <div :key="readEpoch"><ShopProfilePanel v-if="active === 'profile'" :key="'profile'" :initial="shop" @saved="saved" @dirty="dirty = $event" @busy="busy = $event" />
    <BusinessHoursPanel v-if="active === 'hours'" :initial="shop" @saved="saved" @dirty="dirty = $event" @busy="busy = $event" />
    <ServicesPanel v-if="active === 'services'" :service-type="shop.category.code === 'PRINT' ? 'PRINT' : 'HAIRCUT'" @dirty="dirty = $event" @busy="busy = $event" />
    <PrintPricesPanel v-if="active === 'prices'" @dirty="dirty = $event" @busy="busy = $event" />
    <BarbersPanel v-if="active === 'barbers'" @dirty="dirty = $event" @busy="busy = $event" />
    <SchedulePanel v-if="active === 'schedule'" @dirty="dirty = $event" @busy="busy = $event" /></div>
  </template>
</template>
